package fr.uge.ugegreed;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class StringReader implements Reader<String> {

    private enum State {
        DONE, WAITING, ERROR
    };

    private State stateString = State.WAITING;
    private final IntReader intReader = new IntReader();
    private State stateInt = State.WAITING;
    private int size;
    private ByteBuffer buffer = ByteBuffer.allocate(1024);
    private String texte;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (stateString == State.ERROR || stateString == State.DONE) {
            throw new IllegalStateException();
        }
        if (stateInt != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateString = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateInt = State.DONE;
                size = intReader.get();
                if (size < 0 || size > 1024) {
                    stateString = State.ERROR;
                    return ProcessStatus.ERROR;
                }
            }
        }
        if (bb.position() < size) {
            bb.flip();
            size = size - bb.remaining();
            buffer.put(bb);
            bb.clear();
            return ProcessStatus.REFILL;
        } else if (bb.position() == size) {
            bb.flip();
            buffer.put(bb);
            bb.clear();
        } else {
            bb.flip();
            for (int i = 0; i < size; i++) {
                buffer.put(bb.get());
            }
            bb.compact();
        }
        buffer.flip();
        texte = StandardCharsets.UTF_8.decode(buffer).toString();
        stateString = State.DONE;
        return ProcessStatus.DONE;
    }

    @Override
    public String get() {
        if (stateString != State.DONE) {
            throw new IllegalStateException();
        }
        return texte;
    }

    @Override
    public void reset() {
        buffer.clear();
        intReader.reset();
        stateInt = State.WAITING;
        stateString = State.WAITING;
    }
}