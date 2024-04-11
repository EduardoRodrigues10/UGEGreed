package fr.uge.ugegreed;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;

public class IPv6Reader implements Reader<InetAddress> {
    private enum State {
        DONE, WAITING, ERROR
    }

    private State state = State.WAITING;
    private final ByteBuffer buffer = ByteBuffer.allocate(16);
    private InetAddress inetAddress;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (state == State.DONE || state == State.ERROR) {
            throw new IllegalStateException();
        }
        if (bb.remaining() < 16) {
            return ProcessStatus.REFILL;
        }
        bb.get(buffer.array());
        try {
            inetAddress = Inet6Address.getByAddress(buffer.array());
        } catch (Exception e) {
            state = State.ERROR;
            return ProcessStatus.ERROR;
        }
        state = State.DONE;
        return ProcessStatus.DONE;
    }

    @Override
    public InetAddress get() {
        if (state != State.DONE) {
            throw new IllegalStateException();
        }
        return inetAddress;
    }

    @Override
    public void reset() {
        state = State.WAITING;
        buffer.clear();
    }
}
