package org.jesterj.ingest.processors;

import com.copyright.easiertest.Mock;
import org.apache.tika.exception.TikaException;
import org.jesterj.ingest.model.Document;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import static com.copyright.easiertest.EasierMocks.*;
import static org.easymock.EasyMock.aryEq;
import static org.easymock.EasyMock.expect;

public class TikaProcessorTest {

  private static final String HTML = "<html><head><title>The title</title></head><body>This is some body text</body></html>";
  private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><root><peer><child>The title</child></peer><peer>This is some body text</peer></root>";
  private static final String XML_CONFIG=
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
          "<properties>\n" +
          "  <parsers>\n" +
          "    <!-- Default Parser for most things, except for 2 mime types, and never\n" +
          "         use the Executable Parser -->\n" +
          "    <parser class=\"org.apache.tika.parser.DefaultParser\">\n" +
          "      <mime-exclude>image/jpeg</mime-exclude>\n" +
          "      <mime-exclude>application/pdf</mime-exclude>\n" +
          "      <parser-exclude class=\"org.apache.tika.parser.executable.ExecutableParser\"/>\n" +
          "    </parser>\n" +
          "    <!-- Use a different parser for PDF -->\n" +
          "    <parser class=\"org.apache.tika.parser.EmptyParser\">\n" +
          "      <mime>application/pdf</mime>\n" +
          "    </parser>\n" +
          "    <!-- Use a different parser for XML -->\n" +
          "    <parser class=\"org.apache.tika.parser.XmlParser\">\n" +
          "      <mime>text/xml</mime>\n" +
          "    </parser>\n" +
          "  </parsers>\n" +
          "</properties>";
  @Mock
  private Document mockDocument;

  public TikaProcessorTest() {
    prepareMocks(this);
  }

  @Before
  public void setUp() {
    reset();
  }

  @After
  public void tearDown() {
    verify();
  }

  @Test
  public void testHtml() {
    TikaProcessor proc = new TikaProcessor.Builder().named("foo").appendingSuffix("_tk").truncatingTextTo(20).build();
    expect(mockDocument.getRawData()).andReturn(HTML.getBytes()).anyTimes();
    mockDocument.setRawData(aryEq("This is some body te".getBytes()));
    expect(mockDocument.put("X_Parsed_By_tk", "org.apache.tika.parser.DefaultParser")).andReturn(true);
    expect(mockDocument.put("dc_title_tk", "The title")).andReturn(true);
    expect(mockDocument.put("Content_Encoding_tk", "ISO-8859-1")).andReturn(true);
    expect(mockDocument.put("title_tk", "The title")).andReturn(true);
    expect(mockDocument.put("Content_Type_tk", "text/html; charset=ISO-8859-1")).andReturn(true);

    replay();
    proc.processDocument(mockDocument);
  }

  @Test
  public void testXml() throws ParserConfigurationException, IOException, SAXException, TikaException {
    DocumentBuilderFactory factory =
        DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    ByteArrayInputStream input = new ByteArrayInputStream(XML_CONFIG.getBytes("UTF-8"));
    org.w3c.dom.Document doc = builder.parse(input);

    TikaProcessor proc = new TikaProcessor.Builder().named("foo").appendingSuffix("_tk").truncatingTextTo(20)
        .configuredWith(doc)
        .build();
    System.out.println(new String(new byte[] {32, 32, 32, 84, 104, 101, 32, 116, 105, 116, 108, 101, 32, 84, 104, 105, 115, 32, 105, 115}));
    expect(mockDocument.getRawData()).andReturn(XML.getBytes()).anyTimes();
    mockDocument.setRawData(aryEq("   The title This is".getBytes()));
    expect(mockDocument.put("X_Parsed_By_tk", "org.apache.tika.parser.CompositeParser")).andReturn(true);
    expect(mockDocument.put("Content_Type_tk", "application/xml")).andReturn(true);

    replay();
    proc.processDocument(mockDocument);
  }
}
