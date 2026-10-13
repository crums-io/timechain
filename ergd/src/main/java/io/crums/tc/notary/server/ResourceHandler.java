/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary.server;


import static io.crums.tc.notary.server.HttpServerHelp.MimeType.*;

import java.io.IOException;
import java.nio.channels.Channels;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import io.crums.util.io.buffer.BufferUtils;
import io.crums.util.TaskStack;

/**
 * Serves resource files loaded by the classloader.
 * 
 * @see #FILES
 */
public class ResourceHandler implements HttpHandler {

  /**
   * Root path of files served. No other resource paths are
   * intended to be served.
   */
  public final static String FILES = "/files";
  public final static String DIR_GREET = "index.html";
  
  final static String NOT_FOUND_MSG = "Not Found";

  
  

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    if (!HttpServerHelp.screenGetOnly(exchange))
      return;
    
    var uri = FILES + exchange.getRequestURI().toString();
    
    // sanity check URI
    if (uri.contains("../")) {
      HttpServerHelp.sendNotFound(exchange, NOT_FOUND_MSG);
      return;
    }
    if (uri.endsWith("/"))
      uri += DIR_GREET;
    
    try (var closer = new TaskStack()) {
      
      var in = getClass().getResourceAsStream(uri);
      
      if (in == null) {
        // give it another shot before giving up..
        if (!uri.endsWith(DIR_GREET)) {
          uri += "/" + DIR_GREET;
          in = getClass().getResourceAsStream(uri);
        }
        
        if (in == null) {
          HttpServerHelp.sendNotFound(exchange, NOT_FOUND_MSG);
          return;
        }
      }
      
      closer.pushClose(in);
      
      HttpServerHelp.setContentType(exchange, guessContentType(uri));
      
      var buffer = BufferUtils.readFully(in);
      
      var out = exchange.getResponseBody();
      closer.pushClose(out);

      HttpServerHelp.setServer(exchange);
      exchange.sendResponseHeaders(200, buffer.remaining());
      var ch = Channels.newChannel(out);
      do {
        ch.write(buffer);
      } while (buffer.hasRemaining());
    }
  }
  

  
  private String guessContentType(String uri) {
    String ext;
    {
      int dotIndex = uri.lastIndexOf('.');
      if (dotIndex == -1)
        return null;
      ext = uri.substring(dotIndex + 1);
    }
    return 
        switch (ext) {
        case "htm", "html" -> HTML.mime();
        case "txt", "text" -> TEXT.mime();
        case "css"  -> CSS.mime();
        case "js"   -> JS.mime();
        case "png"  -> PNG.mime();
        case "ico"  -> ICO.mime();
        case "jpg", "jpeg"  -> JPEG.mime();
        case "svg"  -> SVG.mime();
        case "json" -> JSON.mime();
        case "xml"  -> XML.mime();
        default     -> null;
        };
  }
  
}























