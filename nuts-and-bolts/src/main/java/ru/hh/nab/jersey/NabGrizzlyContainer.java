package ru.hh.nab.jersey;

import com.sun.grizzly.tcp.http11.GrizzlyAdapter;
import com.sun.grizzly.tcp.http11.GrizzlyRequest;
import com.sun.grizzly.tcp.http11.GrizzlyResponse;
import com.sun.jersey.api.container.ContainerException;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.core.header.InBoundHeaders;
import com.sun.jersey.server.impl.ThreadLocalInvoker;
import com.sun.jersey.spi.container.ContainerListener;
import com.sun.jersey.spi.container.ContainerRequest;
import com.sun.jersey.spi.container.ContainerResponse;
import com.sun.jersey.spi.container.ContainerResponseWriter;
import com.sun.jersey.spi.container.ReloadListener;
import com.sun.jersey.spi.container.WebApplication;
import com.sun.jersey.spi.inject.SingletonTypeInjectableProvider;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import org.slf4j.MDC;

public final class NabGrizzlyContainer extends GrizzlyAdapter implements ContainerListener {
  private WebApplication application;

  private String basePath = "/";

  private final ThreadLocalInvoker<GrizzlyRequest> requestInvoker =
          new ThreadLocalInvoker<GrizzlyRequest>();

  private final ThreadLocalInvoker<GrizzlyResponse> responseInvoker =
          new ThreadLocalInvoker<GrizzlyResponse>();

  private static final String X_REQUEST_ID = "x-request-id";
  private static final String X_HHID_PERFORMER = "x-hhid-performer";
  private static final String X_UID = "x-uid";
  private static final String REQ_REMOTE_ADDR = "req.remote-addr";

  private static class ContextInjectableProvider<T> extends
          SingletonTypeInjectableProvider<Context, T> {

    protected ContextInjectableProvider(Type type, T instance) {
      super(type, instance);
    }
  }

  public NabGrizzlyContainer(ResourceConfig rc, WebApplication app) throws ContainerException {
    this.application = app;

    GenericEntity<ThreadLocal<GrizzlyRequest>> requestThreadLocal =
            new GenericEntity<ThreadLocal<GrizzlyRequest>>(requestInvoker.getImmutableThreadLocal()) {
            };
    rc.getSingletons().add(new ContextInjectableProvider<ThreadLocal<GrizzlyRequest>>(
            requestThreadLocal.getType(), requestThreadLocal.getEntity()));

    GenericEntity<ThreadLocal<GrizzlyResponse>> responseThreadLocal =
            new GenericEntity<ThreadLocal<GrizzlyResponse>>(responseInvoker.getImmutableThreadLocal()) {
            };
    rc.getSingletons().add(new ContextInjectableProvider<ThreadLocal<GrizzlyResponse>>(
            responseThreadLocal.getType(), responseThreadLocal.getEntity()));
  }

  private final static class Writer implements ContainerResponseWriter {
    final GrizzlyResponse response;

    Writer(GrizzlyResponse response) {
      this.response = response;
    }

    public OutputStream writeStatusAndHeaders(long contentLength,
                                              ContainerResponse cResponse) throws IOException {
      response.setStatus(cResponse.getStatus());

      if (contentLength != -1 && contentLength < Integer.MAX_VALUE)
        response.setContentLength((int) contentLength);

      for (Map.Entry<String, List<Object>> e : cResponse.getHttpHeaders().entrySet()) {
        for (Object value : e.getValue()) {
          response.addHeader(e.getKey(), ContainerResponse.getHeaderValue(value));
        }
      }

      String contentType = response.getHeader("Content-Type");
      if (contentType != null) {
        response.setContentType(contentType);
      }

      return response.getOutputStream();
    }

    public void finish() throws IOException {
    }
  }

  public void service(GrizzlyRequest request, GrizzlyResponse response) {
    try {
      requestInvoker.set(request);
      responseInvoker.set(response);

      _service(request, response);
    } finally {
      requestInvoker.set(null);
      responseInvoker.set(null);
    }
  }

  private void _service(GrizzlyRequest request, GrizzlyResponse response) {
    WebApplication _application = application;

    final URI baseUri = getBaseUri(request);
    /*
    * request.unparsedURI() is a URI in encoded form that contains
    * the URI path and URI query components.
    */
    final URI requestUri = baseUri.resolve(
            request.getRequest().unparsedURI().toString());

    /**
     * Check if the request URI path starts with the base URI path
     */
    if (!requestUri.getRawPath().startsWith(basePath)) {
      response.setStatus(404);
      return;
    }

    try {
      final ContainerRequest cRequest = new ContainerRequest(
              _application,
              request.getMethod(),
              baseUri,
              requestUri,
              getHeaders(request),
              request.getInputStream());

      storeHeaderValue(request, X_REQUEST_ID);
      storeHeaderValue(request, X_HHID_PERFORMER);
      storeHeaderValue(request, X_UID);
      MDC.put(REQ_REMOTE_ADDR, request.getRemoteAddr());

      _application.handleRequest(cRequest, new Writer(response));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    } finally {
      removeHeaderValue(X_REQUEST_ID);
      removeHeaderValue(X_HHID_PERFORMER);
      removeHeaderValue(X_UID);
      MDC.remove(REQ_REMOTE_ADDR);
    }
  }

  private void storeHeaderValue(GrizzlyRequest req, String header) {
    MDC.put("req.h." + header, req.getHeader(header));
  }

  private void removeHeaderValue(String header) {
    MDC.remove("req.h." + header);
  }

  @Override
  public void afterService(GrizzlyRequest request, GrizzlyResponse response)
          throws Exception {
  }

  @Override
  public void setResourcesContextPath(String resourcesContextPath) {
    super.setResourcesContextPath(resourcesContextPath);

    if (resourcesContextPath == null || resourcesContextPath.length() == 0) {
      basePath = "/";
    } else if (resourcesContextPath.charAt(resourcesContextPath.length() - 1) != '/') {
      basePath = resourcesContextPath + "/";
    } else {
      basePath = resourcesContextPath;
    }

    if (basePath.charAt(0) != '/')
      throw new IllegalArgumentException("The resource context path, " + resourcesContextPath +
              ", must start with a '/'");
  }

  private URI getBaseUri(GrizzlyRequest request) {
    try {
      return new URI(
              request.getScheme(),
              null,
              request.getServerName(),
              request.getServerPort(),
              basePath,
              null, null);
    } catch (URISyntaxException ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private InBoundHeaders getHeaders(GrizzlyRequest request) {
    InBoundHeaders rh = new InBoundHeaders();

    Enumeration names = request.getHeaderNames();
    while (names.hasMoreElements()) {
      String name = (String) names.nextElement();
      String value = request.getHeader(name);
      rh.add(name, value);
    }

    return rh;
  }

  // ContainerListener

  public void onReload() {
    WebApplication oldApplication = application;
    application = application.clone();

    if (application.getFeaturesAndProperties() instanceof ReloadListener)
      ((ReloadListener) application.getFeaturesAndProperties()).onReload();

    oldApplication.destroy();
  }

  public GrizzlyRequest getGrizzlyRequest() {
    return requestInvoker.get();
  }
}