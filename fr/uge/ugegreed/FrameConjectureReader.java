package fr.uge.ugegreed;

import java.nio.ByteBuffer;

public class FrameConjectureReader implements Reader<FrameConjecture> {
    private enum State {
        DONE, WAITING, ERROR
    };

    private State stateSrc = State.WAITING;
    private State stateDst = State.WAITING;
    private State stateId = State.WAITING;
    private State stateStart = State.WAITING;
    private State stateEnd = State.WAITING;
    private State stateUrlJar = State.WAITING;
    private State stateFullyQualifiedName = State.WAITING;
    private State stateFilename = State.WAITING;

    private IntReader intReader = new IntReader();
    private StringReader stringReader = new StringReader();

    private int src;
    private int dst;
    private int id;
    private int start;
    private int end;
    private String urlJar;
    private String fullyQualifiedName;
    private String filename;

    @Override
    public ProcessStatus process(ByteBuffer bb) {
        if (stateSrc != State.WAITING && stateId != State.WAITING && stateId != State.WAITING && stateStart != State.WAITING && stateEnd != State.WAITING && stateUrlJar != State.WAITING && stateFullyQualifiedName != State.WAITING && stateFilename != State.WAITING) {
            throw new IllegalStateException();
        }
        if (stateSrc != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateSrc = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateSrc = State.DONE;
                src = intReader.get();
                intReader.reset();
            }
        }
        if (stateDst != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateDst = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateDst = State.DONE;
                dst = intReader.get();
                intReader.reset();
            }
        }
        if (stateId != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateId = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateId = State.DONE;
                id = intReader.get();
                intReader.reset();
            }
        }
        if (stateStart != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateStart = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateStart = State.DONE;
                start = intReader.get();
                intReader.reset();
            }
        }
        if (stateEnd != State.DONE) {
            ProcessStatus status = intReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateEnd = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateEnd = State.DONE;
                end = intReader.get();
                intReader.reset();
            }
        }
        if (stateUrlJar != State.DONE) {
            ProcessStatus status = stringReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateUrlJar = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateUrlJar = State.DONE;
                urlJar = stringReader.get();
                stringReader.reset();
            }
        }
        if (stateFullyQualifiedName != State.DONE) {
            ProcessStatus status = stringReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateFullyQualifiedName = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateFullyQualifiedName = State.DONE;
                fullyQualifiedName = stringReader.get();
                stringReader.reset();
            }
        }
        if (stateFilename != State.DONE) {
            ProcessStatus status = stringReader.process(bb);
            if (status == ProcessStatus.ERROR) {
                stateFilename = State.ERROR;
                return ProcessStatus.ERROR;
            } else if (status == ProcessStatus.REFILL) {
                return ProcessStatus.REFILL;
            } else {
                stateFilename = State.DONE;
                filename = stringReader.get();
                stringReader.reset();
            }
        }
        return ProcessStatus.DONE;
    }

    @Override
    public FrameConjecture get() {
        if (stateSrc != State.DONE || stateDst != State.DONE || stateId != State.DONE || stateStart != State.DONE || stateEnd != State.DONE || stateUrlJar != State.DONE || stateFullyQualifiedName != State.DONE || stateFilename != State.DONE) {
            throw new IllegalStateException();
        }
        return new FrameConjecture(src, dst, id, start, end, urlJar, fullyQualifiedName, filename);
    }

    @Override
    public void reset() {
        stateSrc = State.WAITING;
        stateDst = State.WAITING;
        stateId = State.WAITING;
        stateStart = State.WAITING;
        stateEnd = State.WAITING;
        stateUrlJar = State.WAITING;
        stateFullyQualifiedName = State.WAITING;
        stateFilename = State.WAITING;
    }
}
