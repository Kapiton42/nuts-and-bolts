package ru.hh.nab.grizzly.monitoring;

import java.text.Format;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.commons.lang.time.FastDateFormat;
import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.Connection;
import org.glassfish.grizzly.ConnectionProbe;
import org.glassfish.grizzly.IOEvent;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.helpers.MessageFormatter;


public class ConnectionProbeTimingLogger implements ConnectionProbe {
  private static enum Probe {ACCEPT, CONNECT, READ, END_USER_REQUEST, WRITE}
  
  static final Marker TCP_MARKER = MarkerFactory.getMarker("TCP_MARKER");
  private static final String X_REQUEST_ID = "req.h.x-request-id";
  private static final Format formatter = FastDateFormat.getInstance("HH:mm:ss.S");
  private static final int DEFAULT_INITIAL_CAPACITY = 1000;
  public static final int LOG_THRESHOLD =  DEFAULT_INITIAL_CAPACITY * Probe.values().length + 1;
  
  private final ConcurrentMap<String, Long> timingRecords;
  private final ConcurrentMap<String, String> rids;  
  private final long tolerance;
  private final Logger logger;
  
  
  public ConnectionProbeTimingLogger(long tolerance, Logger logger) {
    this.tolerance = tolerance;
    assert logger != null;
    this.logger = logger;
    
    this.timingRecords = new ConcurrentHashMap<>(DEFAULT_INITIAL_CAPACITY * Probe.values().length);
    this.rids = new ConcurrentHashMap<>(DEFAULT_INITIAL_CAPACITY);
  }
  
  @SuppressWarnings("ConstantConditions")
  private void dumpTimings(String key, long closeProbe, boolean error, String errorMessage) {
    Long startAccept = timingRecords.get(makeProbeKey(key, Probe.ACCEPT));
    long total = startAccept != null ? closeProbe - startAccept : -1;
    if (total > tolerance) {
      String rid = rids.get(key);
      if (rid == null) {
        return;
      }
      
      try {        
        MDC.put(X_REQUEST_ID, rid);
        long prevTime = startAccept;
        StringBuilder messageBuilder = new StringBuilder(format("request started at {}, ", formatter.format(startAccept)));
        for (Probe p : Probe.values()) {
          if (Probe.ACCEPT == p) {
            continue;
          }
          
          Long time = timingRecords.get(makeProbeKey(key, p));
          if (time == null) {
            continue;
          }          
          messageBuilder.append(format("{}=+{}, ", p, time - prevTime));
          prevTime = time;
        }

        if (error) {
          messageBuilder.append(format("total={}, completed with error '{}'", total, errorMessage));
          logger.error(TCP_MARKER, messageBuilder.toString());
        } else {
          messageBuilder.append(format("total={}, completed successfully", total));
          logger.warn(TCP_MARKER, messageBuilder.toString());
        }
      } finally {
        MDC.remove(X_REQUEST_ID);
      }
    }
  }

  public void endUserRequest(String requestId, String peerAddress) {
    if (requestId == null || timingRecords.get(makeProbeKey(peerAddress, Probe.ACCEPT)) == null) {
      return;
    }
    rids.put(peerAddress, requestId);
    probe(peerAddress, Probe.END_USER_REQUEST);
  }
      
  @Override
  public void onAcceptEvent(Connection serverConnection, Connection clientConnection) {
    probe(clientConnection, Probe.ACCEPT);
  }

  @Override
  public void onConnectEvent(Connection connection) {
    probe(connection, Probe.CONNECT);
  }

  @Override
  public void onReadEvent(Connection connection, Buffer data, int size) {
    probe(connection, Probe.READ);
  }

  @Override
  public void onWriteEvent(Connection connection, Buffer data, long size) {
    probe(connection, Probe.WRITE);
  }

  @Override
  public void onErrorEvent(Connection connection, Throwable error) {
    end(makeKey(connection), true, error.getMessage());
  }

  @Override
  public void onCloseEvent(Connection connection) {
    end(makeKey(connection), false, null);
  }
  
  @Override
  public void onIOEventReadyEvent(Connection connection, IOEvent ioEvent) {}

  private void clear(String key) {
    for (Probe probe : Probe.values()) {
      timingRecords.remove(makeProbeKey(key, probe));
    }
    rids.remove(key);
  }

  private void end(String key, boolean error, String errorMessage) {
    try {
      int ts = timingRecords.size();
      if (ts > LOG_THRESHOLD) {
        logger.warn(TCP_MARKER, "timingsRecords map too large: " + String.valueOf(ts));
      }
      dumpTimings(key, System.currentTimeMillis(), error, errorMessage);
    } finally {
      clear(key);
    }
  }
  
  private void probe(Connection clientConnection, Probe stage) {
    probe(makeKey(clientConnection), stage);
  }

  private void probe(String key, Probe stage) {
    timingRecords.put(makeProbeKey(key, stage), System.currentTimeMillis());
  }

  private static String format(String t, Object... args) {
    return  MessageFormatter.arrayFormat(t, args).getMessage();
  }

  private static String makeProbeKey(String peerAddress, Probe probe) {
    return peerAddress + probe.name();
  }

  public static String makeKey(Connection clientConnection) {
    return clientConnection.getPeerAddress().toString();
  }

  @Override
  public void onIOEventEnableEvent(Connection connection, IOEvent ioEvent) {}

  @Override
  public void onIOEventDisableEvent(Connection connection, IOEvent ioEvent) {}

  @Override
  public void onBindEvent(Connection connection) {}
}
