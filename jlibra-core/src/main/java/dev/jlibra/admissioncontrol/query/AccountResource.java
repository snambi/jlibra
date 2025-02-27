package dev.jlibra.admissioncontrol.query;

import static dev.jlibra.serialization.Deserialization.readBoolean;
import static dev.jlibra.serialization.Deserialization.readBytes;
import static dev.jlibra.serialization.Deserialization.readInt;
import static dev.jlibra.serialization.Deserialization.readLong;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.immutables.value.Value;

@Value.Immutable
public interface AccountResource {

    byte[] getAuthenticationKey();

    long getBalanceInMicroLibras();

    EventHandle getReceivedEvents();

    EventHandle getSentEvents();

    int getSequenceNumber();

    boolean getDelegatedWithdrawalCapability();

    boolean getDelegatedKeyRotationCapability();

    static AccountResource deserialize(byte[] bytes) {
        try (DataInputStream accountDataStream = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int addressLength = readInt(accountDataStream, 4);
            byte[] address = readBytes(accountDataStream, addressLength);
            long balance = readLong(accountDataStream, 8);
            boolean delegatedKeyRotationCapability = readBoolean(accountDataStream);
            boolean delegatedWithdrawalCapability = readBoolean(accountDataStream);

            int receivedEventsCount = readInt(accountDataStream, 4);
            // skip struct attribute sequence number
            readInt(accountDataStream, 4);
            EventHandle receivedEvents = ImmutableEventHandle.builder()
                    .count(receivedEventsCount)
                    .key(readBytes(accountDataStream, readInt(accountDataStream, 4)))
                    .build();

            int sentEventsCount = readInt(accountDataStream, 4);
            // skip struct attribute sequence number
            readInt(accountDataStream, 4);
            EventHandle sentEvents = ImmutableEventHandle.builder()
                    .key(readBytes(accountDataStream, readInt(accountDataStream, 4)))
                    .count(sentEventsCount)
                    .build();

            return ImmutableAccountResource.builder()
                    .authenticationKey(address)
                    .sequenceNumber(readInt(accountDataStream, 4))
                    .balanceInMicroLibras(balance)
                    .delegatedWithdrawalCapability(delegatedWithdrawalCapability)
                    .delegatedKeyRotationCapability(delegatedKeyRotationCapability)
                    .receivedEvents(receivedEvents)
                    .sentEvents(sentEvents).build();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
