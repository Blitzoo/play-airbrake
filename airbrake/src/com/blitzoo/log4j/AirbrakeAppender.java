package com.blitzoo.log4j;

import org.apache.log4j.AppenderSkeleton;
import org.apache.log4j.spi.LoggingEvent;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import play.Play;
import play.mvc.Http;
import play.mvc.Scope;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Packages errors in XML format and publishes them to the
 * Airbrake.io API
 */
public class AirbrakeAppender extends AppenderSkeleton {
    private final String apiVersion = "2.2";
    private String apiKey = "mykey";
    private String environmentName = "dev";
    private String appVersion = "1.1";

    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public void setEnvironmentName(String environmentName) { this.environmentName = environmentName; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getApiKey() {return apiKey; }
    public String getEnvironmentName() { return environmentName; }
    public String getAppVersion() {return appVersion; }

    private Element addChild(Document doc, Element root, String elementName)
    {
        return addChild(doc, root, elementName, null);
    }

    private Element addChild(Document doc, Element root, String elementName, String elementValue)
    {
        Element el = doc.createElement(elementName);
        if ( null != elementValue ) {
            el.setTextContent(elementValue);
        }
        root.appendChild(el);
        return el;
    }

    private String getUrl()
    {
        if ( null == Http.Request.current() ) {
            return "(null)";
        }
        return Http.Request.current().url;
    }

    private String getComponent()
    {
        if ( null == Http.Request.current() ) {
            return "(null)";
        }
        return Http.Request.current().controller;
    }

    @Override
    protected void append(LoggingEvent loggingEvent) {
        try {
            DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = dbfac.newDocumentBuilder();
            Document doc = docBuilder.newDocument();

            Element notice = doc.createElement("notice");
            notice.setAttribute("version", apiVersion);

            addChild(doc, notice, "api-key", apiKey);

            // Information about notifier
            Element notifier = addChild(doc, notice, "notifier");
            addChild(doc, notifier, "name", "log4j-AirbrakeAppender");
            addChild(doc, notifier, "version", "0.0.2");
            addChild(doc, notifier, "url", "http://www.blitzoo.com");

            // Information about the error encountered
            Element error = addChild(doc, notice, "error");
            Throwable thrown = null;
            StackTraceElement[] stack = null;
            if ( null == loggingEvent.getThrowableInformation() ) {
                addChild(doc, error, "class", "Unknown" );
                stack = Thread.currentThread().getStackTrace();
            } else {
                addChild(doc, error, "class", loggingEvent.getThrowableInformation().getThrowable().getClass().getName() );
                thrown = loggingEvent.getThrowableInformation().getThrowable();
                stack =thrown .getStackTrace();
            }
            addChild(doc, error, "message", loggingEvent.getRenderedMessage());

            // TODO: This is ugly
            Element backtrace = addChild(doc, error, "backtrace");
            if ( null == thrown  ) {
                for ( StackTraceElement stackElement : stack ) {
                    Element line = addChild(doc, backtrace, "line");
                    line.setAttribute("method", stackElement.getMethodName());
                    line.setAttribute("file", stackElement.getFileName());
                    line.setAttribute("number", String.format("%d", stackElement.getLineNumber()));
                }
            } else {
                List<StackTraceElement> cleanTrace = new ArrayList<StackTraceElement>();
                for(int i = 0; i < 5; i++) {
                    for ( StackTraceElement stackElement : thrown.getStackTrace() ) {
                        cleanTrace.add(stackElement);
                    }
                    thrown.setStackTrace(cleanTrace.toArray(new StackTraceElement[cleanTrace.size()]));
                    thrown = thrown.getCause();
                    if ( thrown == null) {
                        break;
                    }
                }

                for ( StackTraceElement stackElement : cleanTrace ) {
                    Element line = addChild(doc, backtrace, "line");
                    line.setAttribute("method", stackElement.getMethodName());
                    line.setAttribute("file", stackElement.getFileName());
                    line.setAttribute("number", String.format("%d", stackElement.getLineNumber()));
                }
            }

            Element request = addChild(doc, notice, "request");
            addChild( doc, request,  "url", getUrl());
            addChild( doc, request, "component", getComponent());
            if (null != Scope.Session.current() && Scope.Session.current().all().size() > 0) {
                Element session = addChild( doc, request, "session");
                for ( String key : Scope.Session.current().all().keySet()) {
                    addChild( doc, session, "var", Scope.Session.current().get(key)).setAttribute("key", key);
                }
            }
            if (null != Http.Request.current() && Http.Request.current().params.all().size() > 0) {
                Element params = addChild( doc, request, "params");
                for ( String key : Http.Request.current().params.all().keySet() ) {
                    addChild( doc, params, "var", Http.Request.current().params.get(key)).setAttribute("key", key);
                }
            }
            Element params = addChild( doc, request, "cgi-data");
            for ( String key : System.getenv().keySet()) {
                addChild( doc, params, "var", System.getenv(key)).setAttribute("key", key);
            }

            // TODO: Security failure - DB passwords are exposed
            for ( String key : Play.configuration.stringPropertyNames()) {
                addChild( doc, params, "var", Play.configuration.getProperty(key)).setAttribute("key", key);
            }

            Element serverEnvironment = addChild(doc, notice, "server-environment");
            addChild(doc, serverEnvironment, "project-root", Play.applicationPath.getAbsolutePath());
            addChild(doc, serverEnvironment, "environment-name", environmentName);
            addChild(doc, serverEnvironment, "app-version", appVersion );

            doc.appendChild(notice);

            /////////////////
            //Output the XML

            //set up a transformer
            TransformerFactory transfac = TransformerFactory.newInstance();
            Transformer trans = transfac.newTransformer();
            trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            trans.setOutputProperty(OutputKeys.INDENT, "yes");

            //create string from xml tree
            StringWriter sw = new StringWriter();
            StreamResult result = new StreamResult(sw);
            DOMSource source = new DOMSource(doc);
            trans.transform(source, result);
            String xmlString = sw.toString();
            System.err.println(xmlString);

            //print xml
            //System.out.println("Here's the xml:\n\n" + xmlString);
            URL url = new URL("http://api.airbrake.io/notifier_api/v2/notices");
            URLConnection conn = url.openConnection();
            conn.setDoOutput(true);
            conn.addRequestProperty("Accept", "text/xml, application/xml");
            conn.addRequestProperty("Content-Type", "text/xml");
            OutputStreamWriter wr = new OutputStreamWriter(conn.getOutputStream());
            wr.write(xmlString);
            wr.flush();

            // Get the response
            BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            while ((line = rd.readLine()) != null) {
                System.err.println(line);
            }
            wr.close();
            rd.close();
        } catch (ParserConfigurationException e) {
            System.err.print(e);
        } catch (TransformerConfigurationException e) {
            System.err.print(e);
        } catch (TransformerException e) {
            System.err.print(e);
        } catch (MalformedURLException e) {
            System.err.print(e);
        } catch (IOException e) {
            System.err.print(e);
        }
    }

    @Override
    public void close() {
        // Nothing to do
    }

    @Override
    public boolean requiresLayout() {
        return false;
    }
}
