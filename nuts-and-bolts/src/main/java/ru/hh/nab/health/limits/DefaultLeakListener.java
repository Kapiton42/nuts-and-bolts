package ru.hh.nab.health.limits;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultLeakListener implements LeakListener {
  private static final Logger LOG = LoggerFactory.getLogger(LeakDetector.class);

  @Override
  public void leakDetected(LeaseToken token) {
    LOG.error("Lease wasn't returned in time, releasing now");
    token.release();
  }
}
