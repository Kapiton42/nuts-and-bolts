package ru.hh.nab.jersey;

import com.sun.jersey.core.provider.jaxb.AbstractJAXBElementProvider;
import com.sun.jersey.spi.inject.Injectable;
import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.ext.Providers;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLStreamException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

public class FilteredXMLJAXBElementProvider extends AbstractJAXBElementProvider {

  @Context
  private Injectable<SAXParserFactory> spf;

  public FilteredXMLJAXBElementProvider(Providers ps) {
    super(ps);
  }

  public FilteredXMLJAXBElementProvider(Providers ps, MediaType mt) {
    super(ps, mt);
  }

  @Produces(MediaType.APPLICATION_XML)
  @Consumes(MediaType.APPLICATION_XML)
  public static final class App extends FilteredXMLJAXBElementProvider {

    public App(@Context Providers ps) {
      super(ps , MediaType.APPLICATION_XML_TYPE);
    }
  }

  @Produces(MediaType.TEXT_XML)
  @Consumes(MediaType.TEXT_XML)
  public static final class Text extends FilteredXMLJAXBElementProvider {

    public Text(@Context Providers ps) {
      super(ps , MediaType.TEXT_XML_TYPE);
    }
  }

  @Produces(MediaType.WILDCARD)
  @Consumes(MediaType.WILDCARD)
  public static final class General extends FilteredXMLJAXBElementProvider {

    public General(@Context Providers ps) {
      super(ps);
    }

    @Override
    protected boolean isSupported(MediaType m) {
      return m.getSubtype().endsWith("+xml");
    }
  }

  protected final JAXBElement<?> readFrom(Class<?> type, MediaType mediaType,
                                          Unmarshaller u, InputStream entityStream) throws JAXBException {
    return u.unmarshal(getSAXSource(spf.getValue(), entityStream), type);
  }

  protected final void writeTo(JAXBElement<?> t, MediaType mediaType, Charset c,
                               Marshaller m, OutputStream entityStream) throws JAXBException {
    try (FilteredXMLStreamWriter filteredXMLStreamWriter = new FilteredXMLStreamWriter(entityStream)) {
      m.marshal(t, filteredXMLStreamWriter);
      filteredXMLStreamWriter.flush();
    } catch (XMLStreamException e) {
      throw new JAXBException(e);
    }
  }
}
