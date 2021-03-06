package ru.hh.nab.example;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import ru.hh.health.monitoring.TimingsLogger;

@Path("/")
@Singleton
public class ExampleRs {
  @Inject
  private Provider<TimingsLogger> loggerProvider;

  @GET
  @Path("/hello")
  public String hello(@DefaultValue("world")
      @QueryParam("name")
      String name) {
    loggerProvider.get().probe("hello.entry-point");
    try {
      return String.format("Hello, %s!", name);
    } finally {
      loggerProvider.get().probe("hello.exit-point");
    }
  }
}
