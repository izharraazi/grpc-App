//Izhar Raazi 
package edu.sjsu.cmpe273.lab2;

import io.grpc.ServerImpl;
import io.grpc.stub.StreamObserver;
import io.grpc.transport.netty.NettyServerBuilder;

import java.util.logging.Logger;

/**
 * Server that manages startup/shutdown of a {@code PollService} server.
 */
public class PollServer{
  private static final Logger logger = Logger.getLogger(PollServer.class.getName());

  /* The port on which the server should run */
  private int port = 50051;
  private ServerImpl server;

  private void start() throws Exception {
    server = NettyServerBuilder.forPort(port)
        .addService(PollServiceGrpc.bindService(new PollServiceImpl()))
        .build().start();
    logger.info("Server started, listening on " + port);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        // Use stderr here since the logger may have been reset by its JVM shutdown hook.
        System.err.println("*** shutting down gRPC server since JVM is shutting down");
        PollServer.this.stop();
        System.err.println("*** server shut down");
      }
    });
  }

  private void stop() {
    if (server != null) {
      server.shutdown();
    }
  }

  /**
   * Main launches the server from the command line.
   */
  public static void main(String[] args) throws Exception {
    final PollServer server = new PollServer();
    server.start();
  }

  private class PollServiceImpl implements PollServiceGrpc.PollService {

    @Override
   public void createPoll(edu.sjsu.cmpe273.lab2.PollRequest request,
        io.grpc.stub.StreamObserver<edu.sjsu.cmpe273.lab2.PollResponse> responseObserver){
		PollResponse reply = PollResponse.newBuilder().setId("123456").build();
		 logger.info("The Moderator Id for which the poll is created is ::"+request.getModeratorId());
      responseObserver.onValue(reply);
      responseObserver.onCompleted();
}
  }
}	
