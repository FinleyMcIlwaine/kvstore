package io.grpc.examples;


import com.google.protobuf.ByteString;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.examples.proto.KeyValueServiceGrpc;
import io.grpc.examples.proto.CreateRequest;
import io.grpc.examples.proto.CreateResponse;
import io.grpc.examples.proto.DeleteRequest;
import io.grpc.examples.proto.DeleteResponse;
import io.grpc.examples.proto.RetrieveRequest;
import io.grpc.examples.proto.RetrieveResponse;
import io.grpc.examples.proto.UpdateRequest;
import io.grpc.examples.proto.UpdateResponse;
import io.grpc.stub.ClientCalls;
import java.util.Random;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performs sample work load, by creating random keys and values, retrieving them, updating them,
 * and deleting them.  A real program would actually use the values, and they wouldn't be random.
 */
final class KvClient {
  private static final Logger logger = Logger.getLogger(KvClient.class.getName());

  private final int MEAN_KEY_SIZE = 64;
  private final int MEAN_VALUE_SIZE = 65536;

  private final RandomAccessSet<ByteString> knownKeys = new RandomAccessSet<>();
  private final Channel channel;

  private AtomicLong rpcCount = new AtomicLong();
  private final Semaphore limiter = new Semaphore(100);

  KvClient(Channel channel) {
    this.channel = channel;
  }

  long getRpcCount() {
    return rpcCount.get();
  }

  /**
   * Does the client work until {@code done.get()} returns true.  Callers should set done to true,
   * and wait for this method to return.
   */
  void doClientWork(AtomicBoolean done) throws InterruptedException {
    Random random = new Random();
    AtomicReference<Throwable> errors = new AtomicReference<>();

    while (!done.get() && errors.get() == null) {
      // Pick a random CRUD action to take.
      int command = random.nextInt(4);
      if (command == 0) {
        doCreate(channel, errors);
        continue;
      }
      synchronized (knownKeys) {
        // If we don't know about any keys, retry with a new random action.
        if (knownKeys.isEmpty()) {
          continue;
        }
      }
      if (command == 1) {
        doRetrieve(channel, errors);
      } else if (command == 2) {
        doUpdate(channel, errors);
      } else if (command == 3) {
        doDelete(channel, errors);
      } else {
        throw new AssertionError();
      }
    }
    if (errors.get() != null) {
      throw new RuntimeException(errors.get());
    }
  }

  /**
   * Creates a random key and value.
   */
  private void doCreate(Channel chan, AtomicReference<Throwable> error)
      throws InterruptedException {
    limiter.acquire();
    ByteString key = createRandomKey();
    ClientCall<CreateRequest, CreateResponse> call =
        chan.newCall(KeyValueServiceGrpc.getCreateMethod(), CallOptions.DEFAULT);
    CreateRequest req = CreateRequest.newBuilder().setKey(key).setValue(randomBytes(MEAN_VALUE_SIZE)).build();
    ListenableFuture<CreateResponse> res = ClientCalls.futureUnaryCall(call, req);
    res.addListener(() ->  {
      rpcCount.incrementAndGet();
      limiter.release();
    }, MoreExecutors.directExecutor());
    Futures.addCallback(res, new FutureCallback<CreateResponse>() {
      @Override
      public void onSuccess(CreateResponse result) {
        synchronized (knownKeys) {
          knownKeys.add(key);
        }
      }

      @Override
      public void onFailure(Throwable t) {
        Status status = Status.fromThrowable(t);
        if (status.getCode() == Code.ALREADY_EXISTS) {
          synchronized (knownKeys) {
            knownKeys.remove(key);
          }
          logger.log(Level.INFO, "Key already existed", t);
        } else {
          error.compareAndSet(null, t);
        }
      }
    }, MoreExecutors.directExecutor());
  }

  /**
   * Retrieves the value of a random key.
   */
  private void doRetrieve(Channel chan, AtomicReference<Throwable> error)
      throws InterruptedException {
    limiter.acquire();
    ByteString key;
    synchronized (knownKeys) {
      key = knownKeys.getRandomKey();
    }

    ClientCall<RetrieveRequest, RetrieveResponse> call =
        chan.newCall(KeyValueServiceGrpc.getRetrieveMethod(), CallOptions.DEFAULT);
    RetrieveRequest req = RetrieveRequest.newBuilder().setKey(key).build();
    ListenableFuture<RetrieveResponse> res = ClientCalls.futureUnaryCall(call, req);
    res.addListener(() ->  {
      rpcCount.incrementAndGet();
      limiter.release();
    }, MoreExecutors.directExecutor());
    Futures.addCallback(res, new FutureCallback<RetrieveResponse>() {
      @Override
      public void onSuccess(RetrieveResponse result) {
        if (result.getValue().size() < 1) {
          error.compareAndSet(null, new RuntimeException("Invalid response"));
        }
      }

      @Override
      public void onFailure(Throwable t) {
        Status status = Status.fromThrowable(t);
        if (status.getCode() == Code.NOT_FOUND) {
          synchronized (knownKeys) {
            knownKeys.remove(key);
          }
          logger.log(Level.INFO, "Key not found", t);
        } else {
          error.compareAndSet(null, t);
        }
      }
    }, MoreExecutors.directExecutor());
  }

  /**
   * Updates a random key with a random value.
   */
  private void doUpdate(Channel chan, AtomicReference<Throwable> error)
      throws InterruptedException {
    limiter.acquire();
    ByteString key;
    synchronized (knownKeys) {
      key = knownKeys.getRandomKey();
    }
    ClientCall<UpdateRequest, UpdateResponse> call =
        channel.newCall(KeyValueServiceGrpc.getUpdateMethod(), CallOptions.DEFAULT);
    UpdateRequest req = UpdateRequest.newBuilder().setKey(key).setValue(randomBytes(MEAN_VALUE_SIZE)).build();
    ListenableFuture<UpdateResponse> res = ClientCalls.futureUnaryCall(call, req);
    res.addListener(() ->  {
      rpcCount.incrementAndGet();
      limiter.release();
    }, MoreExecutors.directExecutor());
    Futures.addCallback(res, new FutureCallback<UpdateResponse>() {
      @Override
      public void onSuccess(UpdateResponse result) {
      }

      @Override
      public void onFailure(Throwable t) {
        Status status = Status.fromThrowable(t);
        if (status.getCode() == Code.NOT_FOUND) {
          synchronized (knownKeys) {
            knownKeys.remove(key);
          }
          logger.log(Level.INFO, "Key not found", t);
        } else {
          error.compareAndSet(null, t);
        }
      }
    }, MoreExecutors.directExecutor());
  }

  /**
   * Deletes the value of a random key.
   */
  private void doDelete(Channel chan, AtomicReference<Throwable> error)
      throws InterruptedException {
    limiter.acquire();
    ByteString key;
    synchronized (knownKeys) {
      key = knownKeys.getRandomKey();
      knownKeys.remove(key);
    }
    ClientCall<DeleteRequest, DeleteResponse> call =
        chan.newCall(KeyValueServiceGrpc.getDeleteMethod(), CallOptions.DEFAULT);
    DeleteRequest req = DeleteRequest.newBuilder().setKey(key).build();
    knownKeys.remove(key);
    ListenableFuture<DeleteResponse> res = ClientCalls.futureUnaryCall(call, req);
    res.addListener(() ->  {
      rpcCount.incrementAndGet();
      limiter.release();
    }, MoreExecutors.directExecutor());
    Futures.addCallback(res, new FutureCallback<DeleteResponse>() {
      @Override
      public void onSuccess(DeleteResponse result) {
      }

      @Override
      public void onFailure(Throwable t) {
        Status status = Status.fromThrowable(t);
        if (status.getCode() == Code.NOT_FOUND) {
          logger.log(Level.INFO, "Key not found", t);
        } else {
          error.compareAndSet(null, t);
        }
      }
    }, MoreExecutors.directExecutor());
  }

  /**
   * Creates and adds a key to the set of known keys.
   */
  private ByteString createRandomKey() {
    ByteString key;
    do {
      key = randomBytes(MEAN_KEY_SIZE);
    } while (!knownKeys.add(key));
    return key;
  }

  /**
   * Creates an exponentially sized byte string with a mean size.
   */
  private static ByteString randomBytes(int mean) {
    Random random = new Random();
    // An exponentially distributed random number.
    int size = (int) Math.round(mean * -Math.log(1 - random.nextDouble()));
    byte[] bytes = new byte[1 + size];
    random.nextBytes(bytes);
    return ByteString.copyFrom(bytes);
  }
}
