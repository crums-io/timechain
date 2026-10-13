/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.client;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

import io.crums.util.tree.fs.DirectoryRemover;
import io.crums.util.api.Opening;
import io.crums.tc.Crum;
import io.crums.tc.Crumtrail;
import io.crums.tc.NotaryPolicy;
import io.crums.tc.except.RepoException;
import io.crums.tc.json.NotaryPolicyParser;
import io.crums.util.IntegralStrings;
import io.crums.util.collections.Lists;
import io.crums.util.RandomId;
import io.crums.util.TidyProperties;

/**
 * Root repo for crumtrails from multiple timechains.
 */
public class Repo {


  /**
   * Returns the default user repo. Creates one, if it doesn't already
   * exist.
   */
  public static Repo userRepo() {
    return userRepo(Opening.CREATE_ON_DEMAND).get();
  }

  /**
   * Opens and returns the default user repo, if one exists, or if
   * the opening mode allows or requires one to be created. Exceptions
   * are still thrown if an assumption is violated (e.g. Opening.CREATE
   * and the repo already exists).
   * 
   * @param mode  opening mode
   * 
   * @see #DEFAULT_NAME
   */
  public static Optional<Repo> userRepo(Opening mode) {
    
    File userRepoDir = defaultUserRepoPath();
    if (!userRepoDir.exists() && mode.exists())
      return Optional.empty();
    
    return Optional.of(new Repo(userRepoDir, mode));
  }



  public static File defaultUserRepoPath() {

    File userHome = new File(System.getProperty("user.home"));
    if (!userHome.isDirectory())
      throw new RepoException("failed to resolve home directory");

    return new File(userHome, DEFAULT_NAME);
  }




  /** Default user repo dir is {@code $HOME/.crums} */
  public final static String DEFAULT_NAME = ".crums";

  /** Files are written once in the staging dir, then moved. */
  public final static String STAGING = "staging";

  /** Repos dir contains remote-chain subdirectories name dby hostname. */
  public final static String REPOS = "repos";

  /** Top level properties file. */
  public final static String CONF_FILE = "crums.conf";
  /** Property name in conf file. */
  public final static String DEFAULT_HOST = "default.host";
  /** Host-specific policy file. In chain (trail) repo dir. */
  public final static String POLICY_FILE = "policy.json";
  /** Host-specific origin file. In chain (trail) repo dir. */
  public final static String ORIGIN_FILE = "origin.conf";

  /** Pending crumtrails recorded using a touch-file protocol in this dir. */
  public final static String PENDING = "pending";




  protected final File root;

  protected final File repos;

  protected final File staging;

  protected final File pending;


  /**
   * Opens or creates an instance at the given path.
   * 
   * @param root  repo's top-level directory
   */
  public Repo(File root) {
    this(root, Opening.CREATE_ON_DEMAND);
  }

  /**
   * Opens or creates an instance at the given path
   * depending on opening mode.
   * 
   * @param root  repo's top-level directory
   * @param mode  opening mode
   */
  public Repo(File root, Opening mode) {
    this.root = root;
    this.repos = mode.ensureDir(new File(root, REPOS));
    this.staging = new File(root, STAGING);
    this.pending = new File(root, PENDING);
    if (!mode.isReadOnly()) {
      mode.ensureDir(staging);
      mode.ensureDir(pending);
    }
  }



  /**
   * Returns the repo's root directory.
   */
  public final File rootDir() {
    return root;
  }



  /**
   * Adds a pending note; returns {@code false} if already added.
   * Note, that on adding, any other pending note with the same
   * witnessed hash ({@link Crum#hash()}) and host but different UTC
   * is removed and replaced by this one.
   */
  public boolean addPending(Crum crum, String host) {

    File note = crumFile(crum, host);
    if (note.exists())
      return false;

    removePending(crum.hash(), host);
    
    try {
      return note.createNewFile();
    
    } catch (IOException iox) {
      throw new RepoException(
        "I/O error on touching " + note + " -- detail: " + iox.getMessage(),
        iox);
    }
  }


  private File crumFile(Crum crum, String host) {
    return new File(
        pending,
        new CrumNote(crum, host).toName());
  }


  /**
   * Removes all pending notes with the same hash and host as the given
   * {@code note} and returns the number removed.
   */
  public int removePending(CrumNote note) {
    return removePending(note.crum().hash(), note.host());
  }



  /**
   * Removes all pending notes with the same hash and host as the given
   * {@code note} and returns the number removed.
   */
  public int removePending(ByteBuffer hash, String host) {
    var notes = listPending(hash, host);
    int count = 0;
    for (var note : notes) {
      if (crumFile(note.crum(), host).delete())
        ++count;
    }
    return count;
  }


  /**
   * Returns a map of all pending crumtrails (crums) key-ed
   * by timechain hostname. Note the returned map may be
   * immutable.
   */
  public Map<String, List<Crum>> pendingByHost() {
    var pending = listPending();
    switch (pending.size()) {
    case 0:   return Map.of();
    case 1:   return Map.of(
                        pending.get(0).host(),
                        List.of(pending.get(0).crum()));
    default:
    }
    Map<String, List<Crum>> crumsByHost = new TreeMap<>();
    for (var note : pending) {
      List<Crum> crums = crumsByHost.get(note.host());
      if (crums == null) {
        crums = new ArrayList<>();
        crumsByHost.put(note.host(), crums);
      }
      crums.add(note.crum());
    }
    return crumsByHost;
  }

  public List<CrumNote> listPending() {
    return listPending("", "");
  }


  public List<CrumNote> listPendingByHost(String host) {
    return listPending("", host);
  }

  public List<CrumNote> listPendingByHash(ByteBuffer hash) {
    return listPending(hash, "");
  }

  public List<CrumNote> listPendingByHash(String hex) {
    return listPending(hex, "");
  }

  public List<CrumNote> listPending(ByteBuffer hash, String host) {
    return listPending(IntegralStrings.toHex(hash), host);
  }


  /**
   * Lists all pending crum notes, with the given hex hash and hostname.
   * The {@code hex} parameter may be specified only by prefix. Similarly,
   * the {@code host} parameter may be specified only be postfix. For e.g.
   * the value '{@code com}' constrains the hosts to all those ending
   * in the {@code .com} domain (note the dot).
   * 
   * @param hex     hex value of hash (prefix OK)
   * @param host    optional hostname (postfix OK)
   */
  public List<CrumNote> listPending(String hex, String host) {
    var suffix = host == null ? "" : host.trim().toLowerCase();
    
    var filter = new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        if (!name.startsWith(hex))
          return false;

        // inefficient.. but doesn't matter
        CrumNote note;
        try {
          note = CrumNote.fromName(name);
        } catch (Exception x) {
          System.err.println("[WARNING] funky crum note file: " + name);
          return false;
        }
        return
            suffix.isEmpty() ||
            note.host().equals(suffix) ||
            note.host().endsWith("." + suffix);
      }
    };

    return CrumNote.fromNames(pending.list(filter));
  }





  /**
   * Each instance represents a pending crumtrail and is persisted
   * as a named empty file.
   * 
   * @see #fromName(String)
   * @see #fromNames(String[])
   */
  public record CrumNote(Crum crum, String host) implements Comparable<CrumNote> {

    public CrumNote {
      crum.utc();
      host = host.toLowerCase().trim();
      if (host.isEmpty())
        throw new IllegalArgumentException("empty host");
    }


    /**
     * Instances are ordered solely by UTC.
     */
    @Override
    public int compareTo(CrumNote other) {
      return Long.compare(crum.utc(), other.crum.utc());
    }

    /**
     * Returns the [file] name.
     * 
     * @see #fromName(String)
     */
    public String toName() {
      return crum.hashHex() + "." + crum.utc() + "." + host;
    }


    public static List<CrumNote> fromNames(String[] names) {
      return
          Arrays.asList(names).stream().map(CrumNote::fromName).toList();
    }


    /**
     * Returns an instance using the naming convention.
     */
    public static CrumNote fromName(String name) {
      final int dot = name.indexOf('.');
      if (dot == -1)
        throw new IllegalArgumentException(name);
      var hex = name.substring(0, dot);
      final int pdot = name.indexOf(".", dot + 1);
      if (pdot == -1)
        throw new IllegalArgumentException(name);
      var sutc = name.substring(dot + 1, pdot);
      var host = name.substring(pdot + 1);
      try {
        byte[] hash = IntegralStrings.hexToBytes(hex);
        long utc = Long.parseLong(sutc);
        return new CrumNote(new Crum(hash, utc), host);
      
      } catch (Exception x) {
        throw new IllegalArgumentException(
          name + " -- detail: " + x.getMessage(), x);
      }

    }
  }


  
  public boolean isEmpty() {
    return !listChainHosts().isEmpty();
  }
  


  /**
   * Sets the default Timechain host.
   * 
   * @param host    <em>existing</em> chain repo's hostname
   */
  public void setDefaultHost(String host) {
    if (!hasChainRepo(host))
      throw new IllegalArgumentException(
          "trail repo for host not found: " + host);

    File confFile = new File(root, CONF_FILE);
    File staged = stageByName(confFile);

    final boolean replace = confFile.exists();

    Properties conf =
        replace ? loadProperties(confFile) : new Properties();

    conf.setProperty(DEFAULT_HOST, host);
    writeProperties(conf, "Local Repo Configuration", staged);

    replace(staged, confFile);
  }


  /**
   * Returns the default chain host, if set, or if only one chain host
   * is known to this repo.
   */
  public Optional<String> getDefaultHost() {
    
    File confFile = new File(root, CONF_FILE);
    if (confFile.exists()) {
      var host = loadProperties(confFile).getProperty(DEFAULT_HOST);
      if (host == null)
        throw new RepoException(
            "missing " + DEFAULT_HOST + " setting in " + confFile);
      return Optional.of(host);
    }
    // o.w. if we only know of one host, then that'll be the default
    var hosts = listChainHosts();
    return hosts.size() == 1 ? Optional.of(hosts.get(0)) : Optional.empty();
  }






  private void writeProperties(Properties props, String comment, File file) {
    try (var out = new FileOutputStream(file)) {
      props.store(out, comment);
    } catch (IOException iox) {
      throw new RepoException(
          "on attempting to write " + file + " -- detail: " + iox.getMessage(),
          iox);
    }
  }

  private Properties loadProperties(File file) {
    try (var in = new FileInputStream(file)) {
      var props = new Properties();
      props.load(in);
      return props;
    } catch (Exception x) {
      throw new RepoException(
          "on attempting to read " + file + " -- detail: " + x.getMessage(),
          x);
    }
  }




  /**
   * Returns a {@code ChainRepo} instance for the given
   * remote chain. If it doesn't exist on the file system, then
   * it's first created before being returned. In that event,
   * {@linkplain RemoteChain#policy() remote.policy()} is
   * also called, and is recorded in the returned repo's
   * policy file.
   * 
   * @param remote    remote timechain / notary service
   * 
   * @return newly loaded or created chain-specific repo
   */
  public ChainRepo getChainRepo(RemoteChain remote) {
    return
        hasChainRepo(remote.hostURI().getHost()) ?
            loadChainRepo(remote) :
            createChainRepo(remote);
  }


  /**
   * Finds and returns the chain repo for the given hostname.
   */
  public Optional<ChainRepo> findChainRepo(String host) {
    return
        hasChainRepo(host) ?
            Optional.of(loadChainRepo(host)) :
            Optional.empty();
  }



  /**
   * Returns the default chain repo, if one is set. If the
   * repo knows of only one chain, then that serves as the
   * default.
   */
  public Optional<ChainRepo> defaultChainRepo() {
    return getDefaultHost().map(this::loadChainRepo);

  }



  /**
   * Lists the timechains known by this repo. Timechains
   * are known by hostname (i.e. not by any other detail
   * such as port no. in the host URI).
   */
  public List<String> listChainHosts() {
    return
        Lists.asReadOnlyList(repos.list());
  }


  /**
   * Loads and returns the chain repos.
   */
  public List<ChainRepo> loadChainRepos() {
    return
        listChainHosts().stream()
        .map(this::loadChainRepo)
        .toList();
  }



  /**
   * Tests whether there is a chain repo with the given hostname.
   */
  public boolean hasChainRepo(String host) {
    return chainDir(host).isDirectory();
  }


  /**
   * Deletes any remnant staged (uncommitted) files or directories
   * created by this instance's JVM.
   * 
   * @return no. of top-level entries deleted
   */
  public int clearStaged() {
    final var prefix = RandomId.RUN_INSTANCE.hexId();
    return removeStaged(
        staging.listFiles(f -> f.getName().startsWith(prefix)) );
  }

  private int removeStaged(File[] staged) {
    for (File f : staged)
      DirectoryRemover.removeTree(f);
    return staged.length;
  }


  /**
   * Deletes <em>all</em> staged files (whether created by this process
   * or another). Needless to say, this can fail under concurrent access
   * (or may cause another process writing to the repo to fail.)
   * 
   * 
   * @return no. of top-level entries deleted
   */
  public int clearAllStaged() {
    return removeStaged(staging.listFiles());
  }


  private File chainDir(String host) {
    return new File(repos, host);
  }


  private ChainRepo createChainRepo(RemoteChain remote) {
    NotaryPolicy policy = remote.policy();
    final URI hostUri = remote.hostURI();
    // dir where the chain repo will land on commit..
    final File dir = chainDir(hostUri.getHost());
    // build the repo in a staged directory
    final File stagedDir = stageByName(dir);
    if (!stagedDir.mkdir())
      throw new RepoException("failed to mkdir " + stagedDir);
    createOrigin(stagedDir, hostUri);
    File policyFile = new File(stagedDir, POLICY_FILE);
    try (var out = new FileWriter(policyFile)) {
      NotaryPolicyParser.INSTANCE.toJsonObject(policy).writeJSONString(out);
    
    } catch (Exception x) {
      throw new RepoException(
          "on writing policy to " + policyFile + " -- detail: " + x.getMessage(),
          x);
    }
    // Create the repo, the final staged step
    // (unnecessary at time of writing, but in case things change..)
    new TrailRepo(stagedDir);

    commit(stagedDir, dir);
    // now dog-food what was written
    return loadChainRepo(remote);
  }

  private void replace(File staged, File target) {
    if (!target.delete() && target.exists())
      throw new RepoException(
          "failed to delete " + target + " for replacement by " + staged);
    if (!staged.renameTo(target))
      throw new RepoException(
        "failed to move (replace) " + staged + " to " + staged);
  }

  private void commit(File staged, File target) {
    if (!staged.renameTo(target))
      throw new RepoException(
          "failed to move (commit) " + staged + " to " + target);
  }

  private File stageByName(File target) {
    return new File(
        staging, RandomId.RUN_INSTANCE.hexId() + "." + target.getName());
  }



  /** Loads and verifies origin before returning chain repo. */
  private ChainRepo loadChainRepo(RemoteChain remote) {
    URI hostUri = remote.hostURI();
    var chainRepo = loadChainRepo(hostUri.getHost());
    if (!hostUri.equals(chainRepo.origin()))
      throw new IllegalArgumentException(
        "origin URL mismatch; expected '" + chainRepo.origin() +
        "', but given remote argument has '" + hostUri + "'");
    return chainRepo;
  }


  /**
   * Chain repo by host name.
   * 
   * @throws IllegalArgumentException
   *          if no such chain host is known to the repo
   * @throws RepoException
   *          if the named chain repo is in invalid state
   */
  public ChainRepo loadChainRepo(String host) throws RepoException {
    final File dir = chainDir(host);
    var hostUri = loadOrigin(dir).hostURI();
    var chainRepo = new ChainRepo(hostUri, dir);

    // assert the policy file is there and works
    chainRepo.policy();

    return chainRepo;
  }



  private ChainOrigin loadOrigin(File dir) {
    final File originConf = new File(dir, ORIGIN_FILE);
    if (!originConf.isFile()) {
      if (dir.isDirectory())
        throw new RepoException("missing origin file: " + originConf);
      else
        throw new IllegalArgumentException(
            "chain hostname not found: " + dir.getName());
    }
    
    var originProps = new Properties();
    try (var in = new FileInputStream(originConf)) {
      originProps.load(in);
    } catch (IOException iox) {
      throw new RepoException(
          "failed to load " + originConf + " -- detail: " + iox.getMessage(),
          iox);
    }
    var host = dir.getName();
    var chainOrigin = ChainOrigin.fromProperties(originProps);
    if (!chainOrigin.host().equals(host))
      throw new RepoException(
          "expected host in " + originConf + " is '" + host +
          "'; actual was '" + chainOrigin.host() + "': " + originProps);
    return chainOrigin;
  }


  private void createOrigin(File dir, URI hostUri) {

    final var origin = new ChainOrigin(hostUri.toString());
    final File originConf = new File(dir, ORIGIN_FILE);
    
    try (var out = new FileOutputStream(originConf)) {

      origin.toProperties().store(out, hostUri.toString());
    
    } catch (IOException iox) {
      throw new UncheckedIOException(
          "on writing to " + originConf + " -- detail: " + iox.getMessage(),
          iox);
    }
  }


  /** Essentially, a URI with constraints. */
  public static class ChainOrigin {

    public final static String SCHEME = "scheme";
    public final static String HOST = "host";
    public final static String PORT = "port";


    
    private final URI hostUri;

    public ChainOrigin(String scheme, String host) {
      this.hostUri = RemoteChain.remoteURI(scheme, host);
    }

    public ChainOrigin(String scheme, String host, int port) {
      this.hostUri = RemoteChain.remoteURI(scheme, host, port);
    }


    public ChainOrigin(String url) {
      if (url.endsWith("/"))
        url = url.substring(0, url.length() - 1);
      
      this.hostUri = RemoteChain.remoteURI(url);
    }


    public final URI hostURI() {
      return hostUri;
    }

    public final String scheme() {
      return hostUri.getScheme();
    }

    public final String host() {
      return hostUri.getHost();
    }

    public final Optional<Integer> port() {
      int port = hostUri.getPort();
      return port == -1 ? Optional.empty() : Optional.of(port);
    }


    public Properties toProperties() {
      var props = new TidyProperties(List.of(SCHEME, HOST, PORT));
      props.setProperty(SCHEME, scheme());
      props.setProperty(HOST, host());
      port().ifPresent(p -> props.setProperty(PORT, Integer.toString(p)));
      return props;
    }


    public static ChainOrigin fromProperties(Properties props) {
      var scheme = requiredValue(props, SCHEME);
      var host = requiredValue(props, HOST);
      var sport = props.getProperty(PORT);

      try {

        return sport == null ?
            new ChainOrigin(scheme, host) :
            new ChainOrigin(scheme, host, Integer.parseInt(sport));

      } catch (NumberFormatException nfx) {
        throw new RepoException(
          "bad port config parameter: " + nfx.getMessage(), nfx);
      }
    }


    private static String requiredValue(Properties props, String name) {
      var value = props.getProperty(name);
      if (value == null)
        throw new RepoException(
          "missing required config parameter '" + name + "'");
      return value;
    }

  }

  

  /**
   * Composition of a {@linkplain TrailRepo} and other information
   * such as the chain's origin URI and {@linkplain NotaryPolicy policy}.
   * This information lives in the same directory as the instance's
   * {@code TrailRepo} directory, but I resisted the temptation to
   * make this a {@code TrailRepo} subclass.
   */
  public static class ChainRepo {

    private final URI origin;

    private final TrailRepo trailRepo;


    private ChainRepo(URI origin, File dir) {
      this(origin, new TrailRepo(dir));
    }

    private ChainRepo(URI origin, TrailRepo trailRepo) {
      this.origin = origin;
      this.trailRepo = trailRepo;
    }



    public URI origin() {
      return origin;
    }


    public NotaryPolicy policy() throws RepoException {
      File policyFile = new File(dir(), POLICY_FILE);
      try {
        return NotaryPolicyParser.INSTANCE.toEntity(policyFile);
      
      } catch (Exception x) {
        throw new RepoException(
          "on reading " + policyFile + " -- detail: " + x.getMessage(), x);
      }
    }


    /**
     * Returns the host-specific crum trail repo. Layout-wise, this, the policy
     * and origin files are all rooted in the same directory.
     */
    public TrailRepo trails() {
      return trailRepo;
    }


    public final File dir() {
      return trailRepo.dir();
    }

  }


  /** A crumtrail paired with the hostname whence it came from. */
  public record HostedTrail(Crumtrail trail, String host) {
    public HostedTrail {
      Objects.requireNonNull(trail, "null trail");
      Objects.requireNonNull(host, "null host");
    }
  }




  /**
   * Finds and returns crumtrails from <em>all</em> hosts (chains)
   * this repo knows about.
   * 
   * @param hash        32-bytes
   * @param incLineage  if {@code true}, then each returned crumtrail (if any)
   *                    contains a hashproof starting from its chain's genesis
   *                    block
   * 
   * @return  immutable, possibly empty list
   */
  public List<HostedTrail> findTrails(ByteBuffer hash, boolean incLineage) {
    var chainRepos = loadChainRepos();
    if (chainRepos.isEmpty())
      return List.of();

    var out = new ArrayList<HostedTrail>(chainRepos.size());
    for (var chainRepo : chainRepos) {
      var trail = chainRepo.trails().findTrail(hash, incLineage);
      if (trail.isPresent())
        out.add(new HostedTrail(trail.get(), chainRepo.origin().getHost()));
    }
    return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
  }




}

