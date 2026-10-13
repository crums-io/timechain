/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary.server;


import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.TreeSet;
import java.util.function.Predicate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import io.crums.sldg.json.HashEncoding;
import io.crums.tc.BlockProof;
import io.crums.tc.Constants;
import io.crums.tc.Crum;
import io.crums.tc.Crumtrail;
import io.crums.tc.Receipt;
import io.crums.tc.json.BlockProofParser;
import io.crums.tc.json.CrumtrailParser;
import io.crums.tc.json.NotaryPolicyParser;
import io.crums.tc.json.ReceiptParser;
import io.crums.tc.notary.Notary;
import io.crums.util.collections.Lists;
import io.crums.util.json.simple.JSONObject;


/**
 * HTTP handlers for the REST API.
 */
public class ApiHandlers {
  

  final static String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
  final static String HTTP_DATE_GMT_TIMEZONE = "GMT";
  
  
  static DateFormat newGmtDateFormat() {
    var dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
    dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
    return dateFormatter;
  }
  

  /**
   * Picks the encoding from the given list of hashes. It must be
   * one or the other; mixed encodings are not supported. If one cannot
   * be picked, then a bad-request (400) is sent, and null is returned.
   */
  static HashEncoding pickEncoding(
      List<String> hashes, HttpExchange exchange)
          throws IOException {
    
    final int len = hashes.get(0).length();
    final HashEncoding encoding;
    if (len == 43) {
      encoding = HashEncoding.BASE64_32;
    } else if (len == 64) {
      encoding = HashEncoding.HEX;
    } else {
      HttpServerHelp.sendBadRequest(
          exchange,
          "does not parse to 32-byte hash: " + Constants.Rest.QS_HASH +
          "=" + hashes.get(0));
      return null;
    }
    for (int index = hashes.size(); index-- > 1; ) {
      int len2 = hashes.get(index).length();
      if (len2 != len) {
        String msg;
        if (len2 + len == 43 + 64) {
          msg = 
              "mixed hash encodings are not supported: " +
              Constants.Rest.QS_HASH + "=" + hashes.get(0) + " ; " +
              Constants.Rest.QS_HASH + "=" + hashes.get(index);
        } else {
          msg =
              "does not parse to 32-byte hash: " + Constants.Rest.QS_HASH +
              "=" + hashes.get(index);
        }

        HttpServerHelp.sendBadRequest(exchange,msg);
        return null;
      } // if (len2 != len
    } // for
    
    return encoding;
  }
  
  
  
  /**
   * Returns the given {@code hash} as a 32-byte buffer. If malformed,
   * then a bad-request (400) is sent and {@code null} is returned.
   */
  static ByteBuffer toBuffer(
      String hash, HashEncoding encoding, HttpExchange exchange)
          throws IOException {
    
    try {
      return ByteBuffer.wrap(encoding.decode(hash));
    
    } catch (Exception x) {
      HttpServerHelp.sendBadRequest(
          exchange,
          "does not parse to 32-byte hash: " + Constants.Rest.QS_HASH +
          "=" + hash);
      return null;
    }
  }


  

  
  
  /**
   * Common utility methods factored out and used by the other handlers
   * (no other rhyme or reason to this).
   */
  static abstract class Base implements HttpHandler {
    
    final Notary notary;
    final ServerSettings settings;
    // final SimpleDateFormat dateFormatter;
    
    
    Base(Notary notary, ServerSettings settings) {
      this.notary = notary;
      this.settings = settings;

      if (!notary.settings().equalPolicy(settings))
        throw new IllegalArgumentException("notary / settings mismatch");
      // this.dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
      // dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));

      if (!notary.isOpen())
        throw new IllegalArgumentException(notary + " is closed");
    }




    @Override
    public final void handle(HttpExchange exchange) throws IOException {
      try {

        handleImpl(exchange);
      
      } catch (IOException | RuntimeException x) {

        StringWriter trace = new StringWriter();
        x.printStackTrace(new PrintWriter(trace));

        var msg = trace.toString();
        System.err.println(msg);

        msg = "\n\nTimechain encountered an internal error:\n\n" + msg;
        HttpServerHelp.sendText(exchange, 500, msg);

        throw x;
      }
    }


    protected abstract void handleImpl(HttpExchange exchange) throws IOException;
    

    /**
     * Returns the list of hashes in the query string.
     * If the returned list is empty, then a 400 level bad-request
     * has already been sent and there is nothing more to do.
     */
    List<String> stringHashes(
        Map<String, List<String>> queryMap, HttpExchange exchange)
            throws IOException {
    
      var hashList = queryMap.get(Constants.Rest.QS_HASH);
      if (hashList == null || hashList.isEmpty()) {
        HttpServerHelp.sendBadRequest(
            exchange,
            "required '" + Constants.Rest.QS_HASH + "' parameter missing");
        return List.of();
      }
      
      if (hashList.size() > settings.maxHashesPerWitness()) {
        HttpServerHelp.sendBadRequest(
            exchange,
            "no. of submitted hashes (" + hashList.size() +
            ") exceeds maximum (" + settings.maxHashesPerWitness() + ")");
        return List.of();
      }
        
      
      return hashList;
    }



    /**
     * Returns the optional compress code. If {@code null} is returned, then it
     * was a bad request (400) and was already handled.
     * 
     * @return  a <em>legal</em> compress code optional, if present; empty
     *          optional, if not present in query map; {@code null}, if it was a
     *          bad request
     *         
     */
    Optional<Integer> getCompressCode(
        Map<String, List<String>> queryMap, HttpExchange exchange)
            throws IOException {

      var codeList = queryMap.get(Constants.Rest.COMPRESS);
      if (codeList == null || codeList.isEmpty())
        return Optional.empty();

      if (codeList.size() > 1) {
        HttpServerHelp.sendBadRequest(
            exchange,
            "'" + Constants.Rest.COMPRESS +
            "' set multiple times in querystring");

        return null;
      }

      int code;
      try {
        code = Integer.parseInt(codeList.get(0));
      } catch (NumberFormatException nfx) {
        HttpServerHelp.sendBadRequest(
            exchange,
            "non-integral value set in '" +
            Constants.Rest.COMPRESS + "=" + codeList.get(0) + "'");

        return null;
      }

      if (code < 0 || code > 1) {
        HttpServerHelp.sendBadRequest(
            exchange,
            "illegal compress code in '" +
            Constants.Rest.COMPRESS + "=" + code + "'");

        return null;
      }

      return Optional.of(code);
    }

    
    /**
     * Returns the optional encoding. If {@code null} is returned, then it
     * was a bad request (400) and was already handled.
     */
    Optional<HashEncoding> getEncoding(
        Map<String, List<String>> queryMap, HttpExchange exchange)
            throws IOException {
      
      var encodingStrs = queryMap.get(Constants.Rest.QS_ENCODING);
      if (encodingStrs == null || encodingStrs.isEmpty())
        return Optional.empty();
      
      if (encodingStrs.size() > 1) {
        HttpServerHelp.sendBadRequest(
            exchange,
            Constants.Rest.QS_ENCODING + " is set more than once (" +
            encodingStrs.size() + ")");
        return null;
      }
      var encoding = encodingStrs.get(0);
      if (Constants.Rest.B64.equals(encoding))
        return Optional.of(HashEncoding.BASE64_32);
      else if (Constants.Rest.HEX.equals(encoding))
        return Optional.of(HashEncoding.HEX);
      
      HttpServerHelp.sendBadRequest(
          exchange,
          "unknown hash encoding: " + Constants.Rest.QS_ENCODING + "=" + encoding);
      return null;
    }




    Long[] getBlockNos(
        Map<String, List<String>> queryMap, HttpExchange exchange)
            throws IOException {


      List<String> blockNoStrings = queryMap.get(Constants.Rest.QS_BLOCK);
      Long[] blockNos;
      try {
        if (blockNoStrings == null)
          blockNos = EMPTY_NOS;
        else if (blockNoStrings.size() == 1)
          blockNos = new Long[] { Long.parseLong(blockNoStrings.get(0)) };
        else {
          var bNos = new TreeSet<Long>();
          blockNoStrings.forEach(s -> bNos.add(Long.parseLong(s)));
          blockNos = bNos.toArray(new Long[bNos.size()]);
          
        }
      } catch (NumberFormatException nfx) {
        nfx.printStackTrace(System.err);
        HttpServerHelp.sendBadRequest(
          exchange,
          "failed to parse block no.: " + nfx.getMessage());
        return null;
      }
      return blockNos;
    }

    private final static Long[] EMPTY_NOS = { };



    long getFromBlockNo(
        Map<String, List<String>> queryMap, HttpExchange exchange)
            throws IOException {

      Long[] blockNos =getBlockNos(queryMap, exchange);
      if (blockNos == null)
        return 0L;
      
      switch (blockNos.length) {
      case 0:   return 1L;
      case 1:
        long bn = blockNos[0];
        if (bn < 1L) {
          HttpServerHelp.sendBadRequest(
              exchange, "out-of-bounds block no.: " + bn);
          return 0;
        }
        return bn;

      default:
        HttpServerHelp.sendBadRequest(
            exchange, "more than one block no.: " + Arrays.asList(blockNos));
        return 0;
      }
    }
    
    
    
  }
  
  
  /** Handler for the "policy" URI endpoint. */
  public static class PolicyHandler extends Base {

    private final NotaryPolicyParser policyParser = new NotaryPolicyParser();

    public PolicyHandler(Notary notary, ServerSettings settings) {
      super(notary, settings);
    }

    @Override
    protected void handleImpl(HttpExchange exchange) throws IOException {
      if (!HttpServerHelp.screenGetOnly(exchange))
        return;
      HttpServerHelp.sendJson(
          exchange,
          200,
          policyParser.toJsonObject(settings));
      
    }
  }
  

  /** Handler for the "witness" URI endpoint. */
  public static class WitnessHandler extends Base {
    
    public WitnessHandler(Notary notary, ServerSettings settings) {
      super(notary, settings);
    }

    @Override
    protected void handleImpl(HttpExchange exchange) throws IOException {
      if (!HttpServerHelp.screenGetOnly(exchange))
        return;
      
      var queryMap = HttpServerHelp.queryMap(exchange);
      
      var strHashes = stringHashes(queryMap, exchange);
      if (strHashes.isEmpty())
        return;
      
      Optional<HashEncoding> encOpt = getEncoding(queryMap, exchange);
      if (encOpt == null)
        return;
      
      HashEncoding enc = pickEncoding(strHashes, exchange);
      if (enc == null)
        return;
      
      List<ByteBuffer> hashes;

      if (strHashes.size() == 1) {
        var buf = toBuffer(strHashes.get(0), enc, exchange);
        if (buf == null)
          return;
        hashes = List.of(buf);
      } else {
        hashes = new ArrayList<>(strHashes.size());
        for (var sh : strHashes) {
          var buf = toBuffer(sh, enc, exchange);
          if (buf == null)
            return;
          hashes.add(buf);
        }
      }

      final boolean compress;
      {
        var compressOpt = getCompressCode(queryMap, exchange);
        if (compressOpt == null)
          return;
        int code = compressOpt.orElse(1);
        compress = code == 1;
      }

      final long fromBlockNo = getFromBlockNo(queryMap, exchange);
      if (fromBlockNo < 1L)
        return;
      
      List<Receipt> receipts;
      try {
        if (hashes.size() == 1)
          receipts = List.of(notary.witness(hashes.get(0), fromBlockNo));
        else {
          receipts = new ArrayList<>(hashes.size());
          for (var hash : hashes)
            receipts.add(notary.witness(hash, fromBlockNo));
        }
      } catch (Exception x) {
        HttpServerHelp.sendText(
            exchange, 500, "internal server error: " + x.getMessage());
        return;
      }
      
      HashEncoding outCodec = encOpt.orElse(HashEncoding.BASE64_32);
      ReceiptParser parser = ReceiptParser.forEncoding(outCodec);
      final Object json;
      final int status;
      if (receipts.size() == 1) {
        var rcpt = receipts.get(0);
        if (compress)
          rcpt = rcpt.compress();
        json = parser.toJsonObject(rcpt);
        status = rcpt.hasTrail() ? 200 : 202;
      
      } else {
        status =
            receipts.stream().anyMatch(Predicate.not(Receipt::hasTrail)) ?
            202 : 200;

        if (compress)
          receipts = Lists.map(receipts, Receipt::compress);
        
        json = parser.toJsonArray(receipts);
      }
      
      HttpServerHelp.sendJson(exchange, status, json);
    }
    

  } // class WitnessHandler
    
    

  /** Handler for the "update" URI endpoint. */
  public static class UpdateHandler extends Base {

    public UpdateHandler(Notary notary, ServerSettings settings) {
      super(notary, settings);
    }
    
    

    @Override
    protected void handleImpl(HttpExchange exchange) throws IOException {

      if (!HttpServerHelp.screenGetOnly(exchange))
        return;
      
      var queryMap = HttpServerHelp.queryMap(exchange);
      
      var strHash =
          HttpServerHelp.requiredSingleValue(
              queryMap,
              Constants.Rest.QS_HASH,
              exchange);
      if (strHash == null)
        return;

      Optional<HashEncoding> encOpt = getEncoding(queryMap, exchange);
      if (encOpt == null)
        return;
      
      HashEncoding enc = pickEncoding(List.of(strHash), exchange);
      if (enc == null)
        return;
      
      var hash = toBuffer(strHash, enc, exchange);
      if (hash == null)
        return;
      
      long utc = getUtc(queryMap, exchange, 0);
      if (utc == 0)
        return;

      final boolean compress;
      {
        var compressOpt = getCompressCode(queryMap, exchange);
        if (compressOpt == null)
          return;
        int code = compressOpt.orElse(1);
        compress = code == 1;
      }

      final long fromBlockNo = getFromBlockNo(queryMap, exchange);
      if (fromBlockNo < 1L)
        return;
      
      Crum crum = new Crum(hash, utc);
      
      Receipt rcpt;
      try {
        rcpt = notary.update(crum, fromBlockNo);
      
      } catch (Exception x) {
        HttpServerHelp.sendText(
            exchange, 500, "internal server error: " + x.getMessage());
        return;
      }

      if (compress)
        rcpt = rcpt.compress();
      
      HashEncoding outCodec = encOpt.orElse(HashEncoding.BASE64_32);
      Object json = ReceiptParser.forEncoding(outCodec).toJsonObject(rcpt);
      
      HttpServerHelp.sendJson(exchange, rcpt.hasTrail() ? 200 : 202, json);
        
    }
    

    
    private long getUtc(
        Map<String, List<String>> queryMap,
        HttpExchange exchange,
        long errorCode)
            throws IOException {
      
      long utc = HttpServerHelp.requiredLongValue(
          queryMap, Constants.Rest.QS_UTC, exchange, errorCode);
      
      if (utc == errorCode)
        return errorCode;
      
      if (utc < notary.chainParams().inceptionUtc()
          || utc > System.currentTimeMillis() +
          notary.settings().maxCrossMachineTimeSkew()) {
        
        HttpServerHelp.sendBadRequest(
            exchange,
            "Out-of-bounds value in query string: " +
            Constants.Rest.QS_UTC + "=" + utc);
        
        return errorCode;
      }
      
      return utc;
        
    }
    
    
  }   // class UpdateHandler
    
  
   
  
  
  /** Handler for the "state" URI endpoint. */
  public static class StateHandler extends Base {

    public StateHandler(Notary notary, ServerSettings settings) {
      super(notary, settings);
    }
    
    

    @Override
    protected void handleImpl(HttpExchange exchange) throws IOException {
      
      if (!HttpServerHelp.screenGetOnly(exchange))
        return;
        
      var queryMap = HttpServerHelp.queryMap(exchange);

      Long[] blockNos = getBlockNos(queryMap, exchange);
      

      var includeLastOpt =
          HttpServerHelp.optionalBooleanValue(
            queryMap, Constants.Rest.QS_LAST, exchange);

      if (includeLastOpt == null)
        return;
      
      final boolean compress;
      {
        var compressOpt = getCompressCode(queryMap, exchange);
        if (compressOpt == null)
          return;
        int code = compressOpt.orElse(1);
        compress = code == 1;
      }
        
      
      
      HashEncoding encoding;
      {
        var opt = getEncoding(queryMap, exchange);
        if (opt == null)
          return;
        encoding = opt.orElse(HashEncoding.BASE64_32);
      }
      
      
      
      BlockProof blockProof;
      try {
        if (blockNos.length == 0)
          blockProof = notary.stateProof();
        else {
          boolean hi = includeLastOpt.orElse(true);
          blockProof = notary.stateProof(hi, blockNos);
        }
      } catch (IllegalArgumentException iax) {
        HttpServerHelp.sendBadRequest(exchange, iax.getMessage());
        return;
      }


      if (compress)
        blockProof = blockProof.compress();
      
      var json = BlockProofParser.forEncoding(encoding).toJsonObject(blockProof);

      HttpServerHelp.sendJson(exchange, 200, json);
    }
     
  }


  public static class VerifyHandler extends Base {

    final SimpleDateFormat dateFormatter;

    public final static String CRUMTRAIL = "crumtrail";

    public VerifyHandler(Notary notary, ServerSettings settings) {
      super(notary, settings);
      this.dateFormatter = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
      dateFormatter.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
    }



    
    

    @Override
    protected void handleImpl(HttpExchange exchange) throws IOException {
      
      if (!HttpServerHelp.screenGetOnly(exchange))
        return;
      
      var queryMap = HttpServerHelp.queryMap(exchange);
      String json;
      {
        var list = queryMap.get(CRUMTRAIL);
        if (list == null || list.size() != 1) {
          String msg =
              (list == null || list.size() == 0) ?
                  "\"" + CRUMTRAIL + "\" parameter missing in request URI" :
                  "multiple \"" + CRUMTRAIL + "\" parameters in request URI";
          
          HttpServerHelp.sendBadRequest(exchange, msg);
          return;
        }

        json = list.getFirst();
        if (json.isEmpty()) {
          var msg = "\"" + CRUMTRAIL + "\" parameter value missing in request URI";
          HttpServerHelp.sendBadRequest(exchange, msg);
          return;
        }
      }


      // create a hex trail parser :/
      // Ugh.. I shouldn't have to pick the hash encoding on the read path
      // no time to fix.. FIXME noted in io.crums.tc.json.BaseParser
      var parser = new CrumtrailParser(false);

      Crumtrail trail;
      try {
        trail = parser.toEntity(json);
      } catch (Exception x) {
        var msg =
            "Malformed request\nType: " + x.getClass().getSimpleName() +
            "\nDetail: " + x.getMessage();
        HttpServerHelp.sendBadRequest(exchange, msg);
        return;
      }

      Crumtrail patchedTrail;
      try {
        var chainState = notary.stateProof(trail.blockNo()).compress();
        patchedTrail = trail.setBlockProof(chainState);
      
      } catch (Exception x) {
        // FIXME: this sends a bad request if the engine fails for *any* reason
        // (i.e. not necessarily a bad request)
        var msg = "No such crumtrail committed to chain.";
        if (trail.blockNo() <= notary.blockCount()) {
          msg += "\nError Type: " + x.getClass().getSimpleName();
          msg += "\nDetail: " + x.getMessage();
        }
        HttpServerHelp.sendBadRequest(exchange, msg);
        return;
      }

      var jObj = new JSONObject();
      var comment =
          trail.crum().hashHex() + " witnessed on " +
          newGmtDateFormat().format(new Date(trail.crum().utc())) +
          ", committed in block [" + trail.blockNo() + "]";
      jObj.put("summary", comment);
      jObj = parser.injectEntity(patchedTrail, jObj);
      
      HttpServerHelp.sendJson(exchange, 200, jObj);
    }
     
  }


}


























