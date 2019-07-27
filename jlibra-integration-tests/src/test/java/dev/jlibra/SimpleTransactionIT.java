package dev.jlibra;

import static admission_control.AdmissionControlOuterClass.AdmissionControlStatus.Accepted;
import static dev.jlibra.mnemonic.Mnemonic.WORDS;
import static java.lang.String.format;
import static java.time.Instant.now;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.with;
import static org.awaitility.pollinterval.FibonacciPollInterval.fibonacci;
import static org.junit.Assert.assertEquals;

import dev.jlibra.admissioncontrol.AdmissionControl;
import dev.jlibra.admissioncontrol.query.ImmutableGetAccountState;
import dev.jlibra.admissioncontrol.query.ImmutableQuery;
import dev.jlibra.admissioncontrol.query.UpdateToLatestLedgerResult;
import dev.jlibra.admissioncontrol.transaction.*;
import dev.jlibra.mnemonic.*;
import dev.jlibra.move.Move;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import com.google.protobuf.ByteString;

import org.apache.commons.lang3.RandomUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.util.encoders.Hex;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 1. Create two key pairs A and B.
 * 2. Mint X libras for account represented by key pair A.
 * 3. Transfer amount Y from A to B and verify the transaction.
 */
public class SimpleTransactionIT {

    private static final Logger logger = LogManager.getLogger(SimpleTransactionIT.class);

    private static final String TEST_SALT = "Salt, pepper and a dash of sugar.";
    private static final String TESTNET_ADDRESS = "ac.testnet.libra.org";
    private static final int TESTNET_PORT = 8000;

    @BeforeClass
    public static void setUpClass() {
        Security.addProvider(new BouncyCastleProvider());
    }

    private ManagedChannel channel;
    private AdmissionControl admissionControl;
    private ExtendedPrivKey sourceAccount;

    @Before
    public void setUp() {
        // source account is fixed
        Mnemonic sourceMnemonic = Mnemonic.fromString("hurry seven priority awful wear jeans antique add fetch sure negative finish suit draft myself chimney spend marine clock furnace draft public erase evidence");
        Seed seed = new Seed(sourceMnemonic, "LIBRA");
        LibraKeyFactory libraKeyFactory = new LibraKeyFactory(seed);
        sourceAccount = libraKeyFactory.privateChild(new ChildNumber(0));

        channel = ManagedChannelBuilder.forAddress(TESTNET_ADDRESS, TESTNET_PORT)
                .usePlaintext()
                .build();

        admissionControl = new AdmissionControl(channel);
    }

    @After
    public void tearDown() {
        channel.shutdown();
    }

    @Test
    public void transferTest() throws IOException {

        // mint something to source account so we don't run out of coins
        mint();

        // destination account is generated
        ExtendedPrivKey destination = generateKey();
        String destinationAddress = destination.getAddress();
        long transactionAmount = 1_000;

        // make the transaction
        transfer(destinationAddress, transactionAmount);

        // wait for balance to become visible
        with().pollInterval(fibonacci().with().timeUnit(SECONDS)).await()
                .atMost(20, SECONDS)
                .untilAsserted(() -> {
                    long actual = findBalance(destinationAddress);
                    String errorMessage = format("Account address: %s, expected balance: %d, actual balance: %d", destinationAddress, transactionAmount, actual);
                    assertEquals(errorMessage, actual, transactionAmount);
                });
    }

    private long findBalance(String forAddress) {
        UpdateToLatestLedgerResult result = admissionControl.updateToLatestLedger(
                ImmutableQuery.builder().addAccountStateQueries(
                        ImmutableGetAccountState.builder().address(Hex.decode(forAddress)).build()
                ).build());

        long balance = result.getAccountStates()
                .stream()
                .filter(accountState -> Arrays.equals(
                        accountState.getAddress(),
                        Hex.decode(forAddress)
                ))
                .map(AccountState::getBalanceInMicroLibras)
                .findFirst()
                .orElse(0L);

        logger.info("Balance for {} is {}", forAddress, balance);

        return balance;
    }

    private void transfer(String toAddress, long amount) throws IOException {

        long sequenceNumber = maybeFindSequenceNumber(admissionControl, sourceAccount.getAddress());

        // Arguments for the peer to peer transaction
        U64Argument amountArgument = new U64Argument(amount);
        AddressArgument addressArgument = new AddressArgument(Hex.decode(toAddress));

        Transaction transaction = ImmutableTransaction.builder()
                .sequenceNumber(sequenceNumber)
                .maxGasAmount(6_000)
                .gasUnitPrice(1_000)
                .expirationTime(now().getEpochSecond() + 1000)
                .program(
                        ImmutableProgram.builder()
                                .code(ByteString.readFrom(Move.peerToPeerTransfer()))
                                .addArguments(addressArgument, amountArgument)
                                .build())
                .build();

        SubmitTransactionResult result = admissionControl.submitTransaction(sourceAccount, transaction);

        assertEquals(Accepted, result.getAdmissionControlStatus());
    }

    private long maybeFindSequenceNumber(AdmissionControl admissionControl, String forAddress) {
        UpdateToLatestLedgerResult result = admissionControl.updateToLatestLedger(
                ImmutableQuery.builder().addAccountStateQueries(
                        ImmutableGetAccountState.builder().address(Hex.decode(forAddress)).build()
                ).build());

        return result.getAccountStates()
                .stream()
                .filter(accountState -> Arrays.equals(
                        accountState.getAddress(),
                        Hex.decode(forAddress)
                ))
                .map(AccountState::getSequenceNumber)
                .findFirst()
                .orElse(0L);
    }

    private void mint() throws IOException {
        long amountInMicroLibras = 1_000_000;

        URL faucet = new URL(
                format("http://faucet.testnet.libra.org?amount=%d&address=%s", amountInMicroLibras, sourceAccount.getAddress()));

        HttpURLConnection con = (HttpURLConnection) faucet.openConnection();
        con.setRequestMethod("GET");
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        int status = con.getResponseCode();

        with().pollInterval(fibonacci().with().timeUnit(SECONDS)).await()
                .atMost(1, MINUTES)
                .until(() -> findBalance(sourceAccount.getAddress()) > 0);

        con.disconnect();

        assertEquals(200, status);
    }

    private ExtendedPrivKey generateKey() {
        String words = IntStream.range(0, 18)
                .map(ignored -> RandomUtils.nextInt(0, WORDS.size()))
                .mapToObj(WORDS::get)
                .collect(Collectors.joining(" "));

        logger.info("Generated seed: {}", words);

        Seed seed = new Seed(Mnemonic.fromString(words), TEST_SALT);

        LibraKeyFactory libraKeyFactory = new LibraKeyFactory(seed);
        return libraKeyFactory.privateChild(new ChildNumber(0));
    }
}
