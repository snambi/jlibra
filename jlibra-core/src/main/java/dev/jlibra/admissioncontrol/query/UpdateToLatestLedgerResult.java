package dev.jlibra.admissioncontrol.query;

import java.util.List;

import org.immutables.value.Value;

@Value.Immutable
public interface UpdateToLatestLedgerResult {

    List<AccountResource> getAccountStates();

    List<SignedTransactionWithProof> getAccountTransactionsBySequenceNumber();

}
