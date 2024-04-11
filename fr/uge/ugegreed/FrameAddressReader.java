package fr.uge.ugegreed;

import java.nio.ByteBuffer;

public class FrameAddressReader implements Reader<FrameAddress> {
    private enum State {
        DONE, WAITING, ERROR
    };

    private State statePort = State.WAITING;

    private final IntReader intReader = new IntReader();

    private int port;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (statePort != State.WAITING) {
            throw new IllegalStateException();
        }
        if (statePort != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                statePort = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                statePort = State.DONE;
                port = intReader.get();
                intReader.reset();
            }
        }
        return ProcessStatus.DONE;
    }

    @Override
    public FrameAddress get() {
        if (statePort != State.DONE) {
            throw new IllegalStateException();
        }
        return new FrameAddress(port);
    }

    @Override
    public void reset() {
        statePort = State.WAITING;
    }
}