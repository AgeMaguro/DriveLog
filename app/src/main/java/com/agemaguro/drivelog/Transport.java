package com.agemaguro.drivelog;

import java.io.IOException;

/** A byte pipe to an ELM327 adapter: real Bluetooth on the phone, a simulated truck for the demo and for tests. */
interface Transport {
    /** Receives what the adapter sends. */
    interface Sink {
        void onData(String text);
        /** The link dropped by itself (not because close() was called). */
        void onClosed(String reason);
    }

    /** Connects (blocks until connected) or throws IOException with a message for the user. */
    void open(String address, Sink sink) throws IOException;

    void write(String text) throws IOException;

    /** Closes the link. Also aborts an open() that is still waiting. Safe to call from any thread, any number of times. */
    void close();
}
