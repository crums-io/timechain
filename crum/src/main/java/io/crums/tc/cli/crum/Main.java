/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.cli.crum;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.OpenOption;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

import io.crums.util.api.Opening;
import io.crums.sldg.HashConflictException;
import io.crums.tc.Constants;
import io.crums.tc.Crumtrail;
import io.crums.tc.NotaryPolicy;
import io.crums.tc.Receipt;
import io.crums.tc.client.Client;
import io.crums.tc.client.RemoteChain;
import io.crums.tc.client.Repo;
import io.crums.tc.except.NetworkException;
import io.crums.tc.except.NetworkException;
import io.crums.tc.json.CrumtrailParser;
import io.crums.util.IntegralStrings;
import io.crums.util.api.Base64_32;
import io.crums.util.Strings;
import io.crums.util.TaskStack;
import io.crums.util.json.JsonPrinter;


import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help.Ansi;
import picocli.CommandLine.HelpCommand;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;
import picocli.CommandLine.ArgGroup;


/**
 * Main launch class.
 * 
 */
@Command(
  name = Main.NAME,
  mixinStandardHelpOptions = true,
  version = Main.VERSION,
  description = {
    "",
    "Records, archives, and retrieves SHA-256 timestamps proving within reasonable",
    "bounds when a hash was witnessed.",
    "See also",
    "    https://crums-io.github.io/timechain/crum_guide.html%n"
  },
  subcommands = {
    HelpCommand.class,
    Peek.class,
    Witness.class,
    Seal.class,
    ListHosts.class,
    Patch.class,
    Find.class,
  }
)
public class Main {

  final static String NAME = "crum";
  final static String VERSION = NAME + " " + Constants.VERSION;



  public static void main(String[] args) throws IOException {
    int exitCode = new CommandLine(new Main()).execute(args);
    System.exit(exitCode);
  }



  /**
   * Printf with picocli markup support. Markup goes in the args.
   * 
   * <h4>Review Note</h4>
   * 
   * Questionable but consevative design choice of not applying the
   * picocli markup after normal printf post-processing: if picocli
   * markup ever interferes with printf "markup", then this strategy
   * mitigates it. Leaving this note to remind why, and not
   * think about it again.
   * 
   * @param format  format string containing no markup
   * @param args    optional markup go here
   */
  static void printf(String format, Object... args) {
    // process string markups with ANSI escape codes
    for (int index = args.length; index-- > 0; ) {
      if (args[index] instanceof String arg)
        args[index] = Ansi.AUTO.string(arg);
    }
    System.out.printf(format, args);
  }



  static void printSealed(Receipt rcpt) {
    Main.printf(
        "[%s] %s%n%8s %s   (Block %d)%n",
        "@|bold,green SEALED|@",
        rcpt.crum().hashHex(),
        "",
        ansiDateCode(rcpt.crum().utc()),
        rcpt.blockNo());
  }

  final static long CURE_LAG_MILLIS = 1500L;

  /**
   * 
   * @param rcpt
   * @param policy
   * @return {@code policy.millisToCommit(rcpt.blockNo()) + CURE_LAG_MILLIS}
   */
  static long printAck(Receipt rcpt, NotaryPolicy policy) {
    final long millisToCommit = millisToCommit(policy, rcpt.blockNo());
    long sealInSeconds = (millisToCommit + 500L) / 1000L;
    Main.printf(
      "[%s]  %s%n%8s %s   (Block %d)%n%8s sealable in about %s%n",
      "@|bold,blue NOTED|@",
      rcpt.crum().hashHex(),
      "",
      ansiDateCode(rcpt.crum().utc()),
      rcpt.blockNo(),
      "",
      Strings.nOf(sealInSeconds, "second"));
    return millisToCommit;
  }


  private static long millisToCommit(NotaryPolicy policy, long blockNo) {
    return policy.millisToCommit(blockNo) + CURE_LAG_MILLIS;
  }



  static String ansiDateCode(long utc) {
    String plain =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
        .format(new Date(utc));
    int dash = plain.indexOf('-') + 1;  // one beyond
    int t = plain.indexOf('T');
    int dot = plain.lastIndexOf('.');
    int zone = dot + 4;
    return
        "@|faint " + plain.substring(0, dash) + "|@" +
        plain.substring(dash, t) + "@|faint T|@" + plain.substring(t + 1, dot) +
        "@|faint " + plain.substring(dot, zone) + "|@@|faint,yellow " +
        plain.substring(zone) + "|@";
  }


  static void printChainInfo(Repo repo, String host) {
    var opt = repo.findChainRepo(host);
    if (opt.isEmpty())
      return;
    
    Repo.ChainRepo chainRepo = opt.get();
    printf("%s%n", "@|bold,underline " + host + "|@");
    final var prefix = " %-18s ";
    final var sFormat = prefix + "%s%n"; // i.e. " %-18s %s%n"
    final var dFormat = prefix + "%d%n";
    printf(
        sFormat,
        "Origin:",
        chainRepo.origin());
    printPolicy(chainRepo.policy(), 1);
    printf(
        " %-" + P_COL_W + "s %d%n",
        "Last repo block:",
        chainRepo.trails().commitNo());
  }

  final static int P_COL_W = 18;

  static void printPolicy(NotaryPolicy policy, int indent) {
    var pad = new StringBuilder();
    while (indent-- > 0)
      pad.append(' ');
    pad.append("%-").append(P_COL_W).append("s ");
    final var prefix = pad.toString();
    final var sFormat = prefix + "%s%n"; // " %-18s %s%n" w/ indent=1
    final var dFormat = prefix + "%d%n";
    var chainParams = policy.chainParams();
    printf(
        sFormat,
        "Inception:",
        ansiDateCode(chainParams.inceptionUtc()));
    printf(
        prefix + "every %.3f seconds%n",
        "Block rate:",
        chainParams.blockDuration() / 1000.0);
    printf(
        prefix + "%.3f seconds%n",
        "Block commit lag:",
        policy.blockCommitLag() / 1000.0);
    printf(
        sFormat,
        "Blocks retained:",
        policy.blocksRetained());
    printf(
        dFormat,
        "Blocks searched:",
        policy.blocksSearched());
    printf(
        dFormat,
        "Current block:",
        chainParams.blockNoNow());
  }


  static void printTrail(Crumtrail trail) {
    printf(
        "%s%n%s .. block [%d]%n",
        trail.crum().hashHex(),
        ansiDateCode(trail.crum().utc()),
        trail.blockNo());
  }




  /** @params msg may be marked up */
  static void printError(String msg) {
    printf("%n%s %s%n", "[@|bold,red ERROR|@]", msg);
  }

  /** @params msg may be marked up */
  static void printError2(String msg) {
    printf("        %s%n", msg);
  }

  /** @param heading may be marked up */
  static void printError(String heading, Exception x) {
    printError(heading);
    var detail = x.getMessage();
    if (detail != null && !detail.isEmpty())
      printError2(detail);
  }

  static void printUnhandledError(Exception x) {
    printError("Unhandled Error", x);
    x.printStackTrace(System.err);
  }

}


abstract class BaseCommand implements Runnable {

  /**
   * Closes stuff on exit. Presently, the only place this may potentially matter
   * is with the HTTP client.
   */
  protected final TaskStack closer = new TaskStack();

  @Override
  public final void run() {
    try (closer) {
      runImpl();
    } catch (ParameterException px) {
      throw px;
    } catch (HashConflictException hcx) {
      Main.printError("Hash proof failure", hcx);
    } catch (Exception x) {
      Main.printUnhandledError(x);
      System.exit(1);
    }
  }

  abstract void runImpl() throws Exception;

}

abstract class NetworkCommand extends BaseCommand {

  @Override
  final void runImpl() {
    try {
      netRun();
    } catch (NetworkException nx) {
      Main.printError("Network / protocol failure", nx);
    }
  }

  abstract void netRun() throws NetworkException;

}


class RepoRoot {
  
  @Spec
  CommandSpec spec;
  
  private File rootDir;
  
  @Option(
      names = { "--repo" },
      paramLabel = "PATH",
      description = {
        "Location of repo root directory",
        "Default: $HOME/.crums"
      },
      required = false
  )
  public void setRootDir(File dir) {
    if (dir.isFile())
      throw new ParameterException(
          spec.commandLine(), "cannot overwrite file: " + dir);
    this.rootDir = dir;
  }
  
  
  public Optional<File> rootDir() {
    return Optional.ofNullable(rootDir);
  }

  
  public Repo openRepo(Opening mode) {
    File repoDir = rootDir().orElse(Repo.defaultUserRepoPath());
    try {
      return new Repo(repoDir, mode);
    } catch (Exception x) {
      var msg =
          (mode.exists() ? "Invalid" : "Failed to create") +
          " repo directory " + repoDir;
      throw new ParameterException(spec.commandLine(), msg);
    }
  }

  
}


class HashOpt {

  @Spec
  CommandSpec spec;

  File file;

  @Option(
    names = { "--file", "-F" },
    paramLabel = "FILE",
    description = "Sets hash to computed SHA-256 of FILE",
    required = true
  )
  void setFile(File file) {
    this.file = file;
    if (!file.isFile())
      throw new ParameterException(spec.commandLine(), "not a file: " + file);
    if (file.length() == 0)
      throw new ParameterException(spec.commandLine(), "empty file: " + file);
  }


  byte[] hash;


  @Option(
    names = { "--hash", "-H" },
    paramLabel = "SHA-256",
    description = {
      "SHA-256 hash expressed in hex or base64/32",
      "Exactly 64 digits in hex, or 43 digits b64"
    }
  )
  void setHash(String hash) {
    
    Exception err;
    try {
      this.hash = switch (hash.length()) {
        case 43 ->  Base64_32.decode(hash);
        case 64 ->  IntegralStrings.hexToBytes(hash);
        default -> null;
      };
      err = null;
    } catch (Exception x) { err = x; }

    if (this.hash == null) {
      var msg = "illegal hash";
      if (err == null)
        msg += " (length=" + hash.length() + "): " + hash;
      else
        msg += ": " + hash + " -- Detail: " + err.getMessage();
      throw new ParameterException(spec.commandLine(), msg);
    }
  }


}




class Origin {

  @Spec
  CommandSpec spec;

  @Option(
    names = { "--host" },
    paramLabel = "HOSTNAME",
    description = {
      "Remote timechain identified by hostname only",
      "If not specified, then the repo's @|italic default|@ host is used",
    }
  )
  String hostname;

  
  URI orginUri;


  @Option(
    names = { "--origin" },
    paramLabel = "URL",
    description = {
      "Timechain notary service identified by host URL",
      "Presently, at most 1 timechain per hostname is modeled",
      "(a workaround is to define aliases). Examples:",
      "",
      "  http://127.0.0.1:8080",
      "  http://localhost:8080",
      "  https://example.crums.io",
      "",
      "Note hostnames are not resolved, so the first 2 examples",
      "generate independent sub-repos."
    }
  )
  void setOriginUri(String origin) {
    var uri = origin.endsWith("/") ?
        origin.substring(0, origin.length() - 1) :
        origin;
    try {
      orginUri = new URI(uri.toLowerCase());
      RemoteChain.checkHostUri(orginUri);
    } catch (Exception err) {
      throw new ParameterException(spec.commandLine(),
          "illegal origin URL: " + origin + " -- Detail: " + err.getMessage());

    }
  }


  boolean isSet() {
    return hostname != null || orginUri != null;
  }
  

}



class SealNow {


  @Option(
    names = { "--wait", "-w"},
    description = {
    "Wait for timechain commit",
    "Default: triggered automatically if timechain blocks span less 4.1 seconds",
    }
  )
  boolean waitAndSeal;

  @Option(
    names = { "--no-wait", "-n"},
    description = {
    "Don't wait for timechain commit (overrides default waiting behavior)"
    }
  )
  boolean dontWait;

}


@Command(
  name = "wit",
  description = {
    "Witness a SHA-256 hash",
    "",
    "The hash is @|italic noted|@ at the timechain notary and an ephemeral receipt (a crum)",
    "is saved; wait for the block to commit, before using @|bold seal|@ to make a permanent",
    "crum trail. If the chain's blocks span less than 4.1 seconds, then this command",
    "also automatically waits and seals (use @|yellow --no-wait|@ or @|yellow --wait|@ to override).",
    "",
  }
)
class Witness extends NetworkCommand {

  
  @Mixin
  RepoRoot repoRoot;

  // @Mixin
  @ArgGroup(exclusive = true, multiplicity="0..1")
  Origin origin;

  private boolean originIsSet() {
    return origin != null && origin.isSet();
  }

  
  @Spec
  CommandSpec spec;



  @ArgGroup(exclusive = true, multiplicity="1")
  HashOpt hashOpt;

  @ArgGroup(exclusive = true, multiplicity="0..1")
  SealNow sealNow;

  @Override
  void netRun() {
    File repoDir = repoRoot.rootDir().orElse(Repo.defaultUserRepoPath());
    if (!repoDir.exists() && !originIsSet())
      throw new ParameterException(spec.commandLine(),
          "--origin URL must be set when creating new a repo" );
    
    Client client = new Client(new Repo(repoDir));

    this.closer.pushClose(client);

    byte[] hash;
    if (hashOpt.hash != null)
      hash = hashOpt.hash;
    else if (hashOpt.file != null)
      hash = computeFileHash(hashOpt.file);
    else
      throw new AssertionError("neither -H or -F options set");

    if (!originIsSet() && client.getDefaultHost().isEmpty())
      throw new ParameterException(spec.commandLine(),
          "undefined default origin in repo " +
          client.repoDir() +
          "\neither --host or --origin must be given");

    
    NotaryPolicy policy;
    String host;

    if (originIsSet()) {

      if (origin.hostname != null) {
        policy = client.getPolicy(origin.hostname).orElseThrow(
            () -> new ParameterException(spec.commandLine(),
                "host " + origin.hostname + " is unknown to repo "+ client.repoDir() +
                "\nset the chain host URL via the --origin option"));
        host = origin.hostname;

      } else {
        host = origin.orginUri.getHost();
        Main.printf(
            "Initializing repo chain for %s%n", 
            "@|bold " + origin.orginUri + "|@");

        policy = client.init(origin.orginUri);
      }

    } else {

      host = client.getDefaultHost().orElseThrow(
          () -> new ParameterException(
              spec.commandLine(),
              "default host is undefined in repo " + client.repoDir() +
              "\nspecify either --host or --origin"));
      policy = client.getPolicy(host).get();
    }

    final var bhash = ByteBuffer.wrap(hash);

    {
      var crumtrail = client.findTrail(bhash, host);
      if (crumtrail.isPresent()) {
        var trail = crumtrail.get();
        Main.printf(
            "Already sealed and notarized on block [%s] %s%n",
            "@|bold " + trail.blockNo() + "|@",
            Main.ansiDateCode(trail.crum().utc()));
        return;
      }
    }

    var rcpt = client.witness(bhash.clear(), host);


    if (rcpt.hasTrail()) {
      Main.printSealed(rcpt);
      return;
    }
    
    long millisToCommit = Main.printAck(rcpt, policy);
    {
      boolean wait;
      if (millisToCommit <= 4100L)  // auto-seal
        wait = sealNow == null || !sealNow.dontWait;
      else
        wait = sealNow != null && sealNow.waitAndSeal;
      if (!wait)
        return;
    }
    System.out.print("waiting..");
    System.out.flush();

    try {
      while (millisToCommit > 0) {
        long bench = System.currentTimeMillis();
        Thread.sleep(millisToCommit);
        millisToCommit -= System.currentTimeMillis() - bench;
      }
    } catch (InterruptedException ix) {
      System.out.printf("Received interrupt.%nExiting.%n");
      return;
    }

    int total = 0;
    int pending = 0;
    System.out.printf("%nsealing trails..%n");
    for (var receipt : client.updatePending(host)) {
      ++total;
      if (receipt.hasTrail())
        Main.printSealed(receipt);
      else {
        Main.printAck(receipt, policy);
        ++pending;
      }
    }

    if (total > 1)
      Main.printf(
          "%s sealed, %s remaining%n",
          Strings.nOf(total - pending, "crum trail"),
          Strings.nOf(pending, "crum"));
  }








  private byte[] computeFileHash(File file) throws UncheckedIOException {
    final long len = file.length();
    if (len <= 0)
      throw new ParameterException(spec.commandLine(),
        "bad filepath " + file);
    var buffer = ByteBuffer.allocate((int) Math.min(len, 64 * 1024));
    var digester = Constants.DIGEST.newDigest();
    try (var ch = new FileInputStream(file).getChannel()) {
      while (ch.read(buffer) != -1) {
        if (buffer.flip().hasRemaining())
          digester.update(buffer);
        buffer.clear();
      }
    } catch (IOException iox) {
      throw new UncheckedIOException("on reading " + file, iox);
    }
    return digester.digest();
  }


}



@Command(
  name = "seal",
  description = {
    "Retrieve and save permanent witness proofs",
    "",
    "See also @|bold wit|@"
  }
)
class Seal extends NetworkCommand {
  
  
  @Spec
  CommandSpec spec;

  @Mixin
  RepoRoot repoRoot;


  @Override
  void netRun() {

    Repo repo = repoRoot.openRepo(Opening.READ_WRITE_IF_EXISTS);

    var pending = repo.pendingByHost();
    if (pending.isEmpty()) {
      System.out.println("Up to date. Nothing to do.");
      return;
    }
    boolean multihost = pending.size() > 1;
    Client client = new Client(repo);
    this.closer.pushClose(client);

    // track how many crums remain to be trailed
    int pendingTally = 0;
    int tally = 0;

    for (var host : pending.keySet()) {
      int count = pending.get(host).size();
      Main.printf(
        "sealing %s from timechain %s%n  ..%n",
        Strings.nOf(count, "crum trail"),
        "@|bold " + host + "|@");
      var rcpts = client.updatePending(host);
      tally += rcpts.size();
      // how many crums remain to be trailed from this host
      int remaining = 0;
      for (var rcpt : rcpts) {
        if (rcpt.hasTrail()) {
          Main.printSealed(rcpt);
        } else {
          Main.printAck(rcpt, client.getPolicy(host).get());
          ++remaining;
        }
      }

      pendingTally += remaining;

      if (remaining > 0) {
        Main.printf(
          "%n%s sealed, %s remaining%n%n",
          Strings.nOf(rcpts.size() - remaining, "crum trail"),
          Strings.nOf(remaining, "crum"));
      } else {
        Main.printf("%n");
      }
    }

    if (multihost) {
      Main.printf(
        "Recap: %s sealed, %s remaining%n",
        Strings.nOf(tally - pendingTally, "crum trail"),
        Strings.nOf(pendingTally, "crum"));
    }

  }

}


// TODO: print default host
@Command(
  name = "hosts",
  description = {
    "List timechain hosts recorded in repo"
  }
)
class ListHosts extends BaseCommand {

  @Mixin
  RepoRoot repoRoot;


  @Option(
    names = { "--info" },
    description = {
      "Include basic info about each timechain"
    }
  )
  boolean detail;


  @Override
  void runImpl() {
    Repo repo = repoRoot.openRepo(Opening.READ_ONLY);
    var hosts = repo.listChainHosts();
    var out = System.out;
    if (detail) {
      for (var host : hosts) {
        Main.printChainInfo(repo, host);
        out.println();
      }
      out.println(Strings.nOf(hosts.size(), "host"));
    } else {
      for (var host : hosts) {
        out.println(host);
      }
    }
  }

}



@Command(
  name = "peek",
  description = {
    "Displays info about the timechain at given URL",
    "Does not interact with repo."
  }
)
class Peek extends NetworkCommand {

  @Spec
  CommandSpec spec;

  @Parameters(
    index = "0",
    paramLabel = "URL",
    description = {
      "Origin URL of the timechain server",
      "The format is http@|italic s|@://example.com@|italic :8585|@",
      "(italicized parts may be missing)"
    }
  )
  void setOriginUrl(String url) {
    try {
      origin = new URI(url);
    } catch (Exception x) {
      throw new ParameterException(
          spec.commandLine(),
          "malformed timechain origin URL: " + url);
    }
    try {
      RemoteChain.checkHostUri(origin);
    } catch (Exception x) {
      throw new ParameterException(
          spec.commandLine(),
          "malformed timechain origin URL: " + url);
    }
  }
  private URI origin;



  @Override
  void netRun() {

    var out = System.out;

    try (var remote = new RemoteChain(origin)) {

      remote.defaultCompression(true);
      var policy = remote.policy();
      out.println();
      Main.printPolicy(policy, 0);
      out.printf(
          "%-" + Main.P_COL_W + "s ",
          "Blocks committed:");

      var state = remote.stateProof();
      out.printf("%d%n", state.blockNo());

    } catch (HashConflictException hcx) {
      Main.printf(
          "%nBlock hash proof linking genesis to frontier %s!%nDetail: %s%n",
          "@|italic,red fails|@",
          hcx.getMessage());
    }
  }

}



@Command(
  name = "patch",
  description = {
    "Patch (update) repo proofs with chain state",
  }
)
class Patch extends NetworkCommand {

  @Mixin
  RepoRoot repoRoot;


  @Spec
  CommandSpec spec;

  // couldn't find a good way to avoid this duplication
  @Option(
    names = { "--host" },
    paramLabel = "HOSTNAME",
    required = false,
    description = {
      "Remote timechain identified by hostname only",
      "If not specified, then the repo's @|italic default|@ host is used",
    }
  )
  void setHost(String host) {
    this.hostname = host.toLowerCase();
  }
  String hostname;


  @Override
  void netRun() {
    Repo repo = repoRoot.openRepo(Opening.READ_WRITE_IF_EXISTS);

    if (hostname == null)
      hostname = repo.getDefaultHost().orElseThrow(() ->
          new ParameterException(
              spec.commandLine(),
              "either specify a --host, or define a default host"));

    Client client = new Client(repo);
    closer.pushClose(client);

    var params =
        client.getPolicy(hostname)
        .orElseThrow(() ->
          new ParameterException(
              spec.commandLine(),
              "chain hostname unknown to repo: " + hostname))
        .chainParams();

    final long commitNo = client.repoCommitNo(hostname);
    assert commitNo > 0L;
    
    final long newCommitNo = client.patchChain(hostname);
    assert newCommitNo >= commitNo;

    Main.printf(
        "%s patched%nRepo is at block [%d]: %s%n",
        Strings.nOf(newCommitNo - commitNo, "block"),
        newCommitNo,
        Main.ansiDateCode(params.utcForBlockNo(newCommitNo + 1) - 1L));
  }


}




class JsonOpt {


  @Option(
    names = { "-j", "--json"} ,
    description = {
      "Output JSON only",
    }
  )
  boolean json;

  @Option(
    names = { "--pretty-json", "--pj"},
    description = {
      "Emit easier-to-read, indented JSON",
    }
  )
  boolean prettyJson;
}




@Command(
  name = "find",
  description = {
    "Search and output crum trail matching given hash prefix",
  }
)
class Find extends BaseCommand {

  @Mixin
  RepoRoot repoRoot;

  @ArgGroup(exclusive = true, multiplicity="0..1")
  JsonOpt jsonOpt;

  boolean isJson() {
    return jsonOpt != null;
  }


  boolean isJsonPretty() {
    return isJson() && jsonOpt.prettyJson;
  }



  @Spec
  CommandSpec spec;

  private String hex;


  @Parameters(
    index = "0",
    paramLabel = "HEX",
    description = {
      "SHA-256 hash prefix in hexadecimal digits",
    }
  )
  public void setHex(String hex) {
    this.hex = hex.toLowerCase().trim();
    checkHex();
  }

  private void checkHex() {
    
    if (hex.length() > 64)
      throw new ParameterException(
          spec.commandLine(),
          "too many hex digits (%d): %s".formatted(hex.length(), hex));
    
    var testHex = (hex.length() & 1) == 0 ? hex : hex + "0";
    if (!IntegralStrings.isHex(testHex))
      throw new ParameterException(
          spec.commandLine(),
          "illegal hex: " + hex);
    
    
  }


  // couldn't find a good way to avoid this duplication
  @Option(
    names = { "--host" },
    paramLabel = "HOSTNAME",
    description = {
      "Remote timechain identified by hostname only",
      "Defaults to repo's @|italic default|@ host",
    }
  )
  String hostname;


  @Option(
    names = { "-g" },
    description = {
      "Include genesis block [1] in crum trail block proof",
      "(JSON only)"
    }
  )
  boolean includeGenesis;



  @Option(
    names = { "--expanded" },
    description = {
      "Output crum trail hash proof in expanded format",
      "(JSON only)"
    }
  )
  boolean expanded;


  @Option(
    names = { "--hex-only" },
    description = {
      "Always output hashes in hexadecimal digits",
      "JSON output defaults to base64 (43 chars / hash)",
      "Ordinary output defaults to hex (64 chars / hash)"
    }
  )
  boolean hexOnly;
  





  @Override
  void runImpl() {

    Repo repo = repoRoot.openRepo(Opening.READ_ONLY);

    if (hostname == null) {

      hostname = repo.getDefaultHost().orElseThrow(
          () -> new ParameterException(
              spec.commandLine(),
              "No default host defined in repo"));
    }
    var chainRepo =
        repo.findChainRepo(hostname).orElseThrow(
            () -> new ParameterException(
                spec.commandLine(),
                "Hostname not found: " + hostname));

    var out = System.out;
    var hexList = chainRepo.trails().findTrailHashes(hex, 16);

    // if there's not exactly 1 hit, exit
    {
      final int hits = hexList.size();
      switch (hits) {
      case 0:
        out.println("Not found.");
        return;
      case 1:
        break;
      default:
        boolean orMore = hits == 16;
        int digitsNeeded = orMore ? 2 : 1;
        out.printf(
            "Ambiguous. %d%s hits.%nEnter %s%n",
            hits,
            orMore ? " or more" : "",
            Strings.nOf(digitsNeeded, "more hex digit"));
        return;
      }
    }

    var trail =
        chainRepo.trails()
        .findTrailByHex(hexList.getFirst(), includeGenesis).get();

    if (isJson()) {
      
      var jObj = new CrumtrailParser(!hexOnly).toJsonObject(
          expanded ? trail : trail.compress());

      if (isJsonPretty())
        JsonPrinter.println(jObj);
      else
        out.println(jObj.toJSONString());
    
    } else {
      Main.printTrail(trail);
    }
    

  }



}




