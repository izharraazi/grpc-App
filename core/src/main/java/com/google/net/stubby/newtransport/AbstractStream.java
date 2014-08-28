package com.google.net.stubby.newtransport;

import com.google.common.base.Preconditions;
import com.google.common.io.Closeables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.net.stubby.Status;

import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.annotation.Nullable;

/**
 * Abstract base class for {@link Stream} implementations.
 */
public abstract class AbstractStream implements Stream {

  /**
   * Indicates the phase of the GRPC stream in one direction.
   */
  protected enum Phase {
    CONTEXT, MESSAGE, STATUS
  }

  private final Object writeLock = new Object();
  private final MessageFramer framer;
  protected Phase inboundPhase = Phase.CONTEXT;
  protected Phase outboundPhase = Phase.CONTEXT;

  /**
   * Handler for Framer output.
   */
  private final Framer.Sink<ByteBuffer> outboundFrameHandler = new Framer.Sink<ByteBuffer>() {
    @Override
    public void deliverFrame(ByteBuffer frame, boolean endOfStream) {
      sendFrame(frame, endOfStream);
    }
  };

  /**
   * Handler for Deframer output.
   */
  private final GrpcMessageListener inboundMessageHandler = new GrpcMessageListener() {
    @Override
    public void onContext(String name, InputStream value, int length) {
      ListenableFuture<Void> future = null;
      try {
        inboundPhase(Phase.CONTEXT);
        future = listener().contextRead(name, value, length);
      } finally {
        closeWhenDone(future, value);
      }
    }

    @Override
    public void onPayload(InputStream input, int length) {
      ListenableFuture<Void> future = null;
      try {
        inboundPhase(Phase.MESSAGE);
        future = listener().messageRead(input, length);
      } finally {
        closeWhenDone(future, input);
      }
    }

    @Override
    public void onStatus(Status status) {
      inboundPhase(Phase.STATUS);
    }
  };

  protected AbstractStream() {
    framer = new MessageFramer(outboundFrameHandler, 4096);
    // No compression at the moment.
    framer.setAllowCompression(false);
  }

  /**
   * Free any resources associated with this stream. Subclass implementations must call this
   * version.
   */
  public void dispose() {
    synchronized (writeLock) {
      framer.dispose();
    }
  }

  @Override
  public final void writeContext(String name, InputStream value, int length,
      @Nullable Runnable accepted) {
    Preconditions.checkNotNull(name, "name");
    Preconditions.checkNotNull(value, "value");
    Preconditions.checkArgument(length >= 0, "length must be >= 0");
    outboundPhase(Phase.CONTEXT);
    synchronized (writeLock) {
      if (!framer.isClosed()) {
        framer.writeContext(name, value, length);
      }
    }

    // TODO(user): add flow control.
    if (accepted != null) {
      accepted.run();
    }
  }

  @Override
  public final void writeMessage(InputStream message, int length, @Nullable Runnable accepted) {
    Preconditions.checkNotNull(message, "message");
    Preconditions.checkArgument(length >= 0, "length must be >= 0");
    outboundPhase(Phase.MESSAGE);
    synchronized (writeLock) {
      if (!framer.isClosed()) {
        framer.writePayload(message, length);
      }
    }

    // TODO(user): add flow control.
    if (accepted != null) {
      accepted.run();
    }
  }

  @Override
  public final void flush() {
    synchronized (writeLock) {
      if (!framer.isClosed()) {
        framer.flush();
      }
    }
  }

  /**
   * Sends an outbound frame to the remote end point.
   *
   * @param frame a buffer containing the chunk of data to be sent.
   * @param endOfStream if {@code true} indicates that no more data will be sent on the stream by
   *        this endpoint.
   */
  protected abstract void sendFrame(ByteBuffer frame, boolean endOfStream);

  /**
   * Returns the listener associated to this stream.
   */
  protected abstract StreamListener listener();

  /**
   * Gets the handler for inbound messages. Subclasses must use this as the target for a
   * {@link com.google.net.stubby.newtransport.Deframer}.
   */
  protected GrpcMessageListener inboundMessageHandler() {
    return inboundMessageHandler;
  }

  /**
   * Transitions the inbound phase. If the transition is disallowed, throws a
   * {@link IllegalStateException}.
   */
  protected final void inboundPhase(Phase nextPhase) {
    inboundPhase = verifyNextPhase(inboundPhase, nextPhase);
  }

  /**
   * Transitions the outbound phase. If the transition is disallowed, throws a
   * {@link IllegalStateException}.
   */
  protected final void outboundPhase(Phase nextPhase) {
    outboundPhase = verifyNextPhase(outboundPhase, nextPhase);
  }

  /**
   * Closes the underlying framer.
   *
   * <p>No-op if the framer has already been closed.
   *
   * @param status if not null, will write the status to the framer before closing it
   */
  protected final void closeFramer(@Nullable Status status) {
    synchronized (writeLock) {
      if (!framer.isClosed()) {
        if (status != null) {
          framer.writeStatus(status);
        }
        framer.close();
      }
    }
  }

  private Phase verifyNextPhase(Phase currentPhase, Phase nextPhase) {
    if (nextPhase.ordinal() < currentPhase.ordinal() || currentPhase == Phase.STATUS) {
      throw new IllegalStateException(
          String.format("Cannot transition phase from %s to %s", currentPhase, nextPhase));
    }
    return nextPhase;
  }

  /**
   * If the given future is provided, closes the {@link InputStream} when it completes. Otherwise
   * the {@link InputStream} is closed immediately.
   */
  private static void closeWhenDone(@Nullable ListenableFuture<Void> future,
      final InputStream input) {
    if (future == null) {
      Closeables.closeQuietly(input);
      return;
    }

    // Close the buffer when the future completes.
    future.addListener(new Runnable() {
      @Override
      public void run() {
        Closeables.closeQuietly(input);
      }
    }, MoreExecutors.directExecutor());
  }
}