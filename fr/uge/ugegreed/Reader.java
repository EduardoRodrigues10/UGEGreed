package fr.uge.ugegreed;

import java.nio.ByteBuffer;

public interface Reader<T> {

    public static enum ProcessStatus {
        DONE, REFILL, ERROR
    };

    public ProcessStatus process(ByteBuffer bb);

    public T get();

    public void reset();

}