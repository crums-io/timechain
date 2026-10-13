/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import static io.crums.tc.notary.NotaryConstants.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Objects;

import io.crums.util.io.FileUtils;
import io.crums.util.api.Opening;
import io.crums.util.io.channels.ChannelUtils;
import io.crums.stowkwik.io.HexPathTree;
import io.crums.tc.CargoProof;
import io.crums.tc.ChainParams;
import io.crums.tc.Constants;
import io.crums.tc.Crum;
import io.crums.tc.NotaryPolicy;
import io.crums.tc.except.TimeChainException;
import io.crums.tc.notary.except.NotaryException;
import io.crums.tc.notary.except.NotaryFileException;
import io.crums.util.IntegralStrings;
import io.crums.util.RandomId;
import io.crums.util.TaskStack;

/**
 * Each cargo block is associated with a new (committed or to-be-committed)
 * timechain block.
 * 
 * <h2>FIXME</h2>
 * <p>
 * There should only be a single cargo-build file, not 2. This flaw presents
 * race-conditions in which the timechain could get corrupted.
 * </p>
 */
public class CargoBlock {
  
  /**
   * State of an instance. Note, we'll need an EMPTY case in this enumeration
   * (see race condition note in {@link CargoBlock} class description).
   * 
   * @see CargoBlock#state()
   */
  public enum State {
    /** Not built. */
    UNBUILT,
    /** One or zero crums makes cargo hash: whash file exists. */
    LONE,
    /** Merkle tree root makes cargo hash: mrkl file exists. */
    MRKL;
    
    
    public boolean isBuilt() {
      return this != UNBUILT;
    }
  }
  
  /**
   * {@linkplain CargoBlock} constructor args.
   */
  public record InitArgs(
      NotaryPolicy policy,
      long blockNo,
      File dir,
      NotaryLog log,
      boolean readOnly) {
    
    public InitArgs {
      Objects.requireNonNull(policy);
      if (blockNo < 0)
        throw new IllegalArgumentException("blockNo: " + blockNo);
      Objects.requireNonNull(dir);
      Objects.requireNonNull(log);
    }
    
    
    public InitArgs(
        NotaryPolicy policy,
        long blockNo,
        File dir,
        NotaryLog log) {
      
      this(policy, blockNo, dir, log, false);
    }
    
    public ChainParams chainParams() {
      return policy.chainParams();
    }
  }


  private final NotaryPolicy policy;
  private final ChainParams chainParams;
  private final long blockNo;
  private final File dir;
  private final File stagingDir;
  private final HexPathTree crumsHexTree;
  
  private final NotaryLog log;
  
  
  public CargoBlock(InitArgs args) {
    this.policy = args.policy();
    this.chainParams = args.chainParams();
    this.blockNo = args.blockNo();
    this.dir = args.dir();
    this.stagingDir = new File(dir, STAGING_DIR);
    try {
      FileUtils.ensureDir(args.readOnly() ? dir : stagingDir);
    } catch (Exception x) {
      var nx = new NotaryException(
          "on block cargo <init> [" + blockNo + "]: " + x.getMessage(),
          x);
      args.log().fatal(nx);
      throw nx;
    }
    this.crumsHexTree = new HexPathTree(dir, CRUM_EXT);
    this.log = args.log();
  }
  
  
  
  /**
   * Returns the state of the instance.
   * Instances transition from {@linkplain State#UNBUILT UNBUILT} to
   * one of {@linkplain State#MRKL MRKL} or {@linkplain State#LONE LONE}.
   */
  public State state() {
    if (mrklFile().exists())
      return State.MRKL;
    if (whashFile().exists())
      return State.LONE;
    return State.UNBUILT;
  }
  
  
  /**
   * Builds the block and returns the number of crums in it.
   * If the block is already built (or on the off chance
   * it has no crums in it--for whatever reason), zero is
   * returned.
   */
  public CargoHash buildCargo() throws NotaryException {
    
    // verify the policy block-commit lag
    {
      final long now = System.currentTimeMillis();
      long maxBlockUtc = chainParams.utcForBlockNo(blockNo + 1);  // (exc)
      long lag = now - maxBlockUtc;
      assert lag >= 0;
      if (lag < policy.blockCommitLag()) {
        var nx = new NotaryException(
            "attempt to build cargo block [" + blockNo +
            "] ahead of block-commmit lag (policy: " + policy.blockCommitLag() +
            ", actual: " + lag + " ms)");
        
        // do not log.fatal(nx);
        throw nx;
      }        
    }
    
    // if the block is already built return cargo hash
    if (state().isBuilt())
      return cargoHash();

    
    // prepare a merkle tree builder and add all the crums in this
    // [cargo] block
    var builder = new CrumTreeBuilder(chainParams, blockNo, log);
    builder.addAll( crumsHexTree.stream().map(e -> toCrum(e)));
    
    final int cc = builder.count();
    
    // if there are one or zero crums, then write the whash file, instead
    if (cc < 2) {
      var crum = builder.first();
      writeWhash(crum);
      return crum == null ? CargoHash.EMPTY : new CargoHash(crum.witnessHash(), 1);
    }
    
    // o.w. build the merkle tree to staged file, then move it..
    
    var staged = newStagedFile(MRKL, "." + MRKL);
    var merkleRoot = builder.buildToTarget(staged);
    
    moveStaged(staged, mrklFile());
    
    
    return new CargoHash(merkleRoot, cc);
  }
  
  
  
  private Crum toCrum(HexPathTree.Entry e) {
    byte[] hash = IntegralStrings.hexToBytes(e.hex);
    long utc = utcAtHexPath(e.file);
    return new Crum(hash, utc);
  }
  
  
  protected final long utcAtHexPath(File file) {
    final long utc;
    try (var closer = new TaskStack()) {
      var utcBuffer = ByteBuffer.allocate(8);
      
      @SuppressWarnings("resource")
      var ch = new FileInputStream(file).getChannel();
      closer.pushClose(ch);
      
      utc = ChannelUtils.readRemaining(ch, utcBuffer).flip().getLong();
      
      
    
    } catch (Exception x) {
      var nx = new NotaryException(
          "on reading UTC from " + file + ": " + x.getMessage(), x);
      log.fatal(nx);
      throw nx;
    }
    
    return assertUtcInBlock(utc, file);
  }
  
  
  /**
   * Writes the crum's witnessed hash, followed
   * by the crum itself. If the crum is {@code null},
   * only the sentinel hash is written.
   * 
   * @param crum    (might be {@code null})
   */
  private boolean writeWhash(Crum crum) {
    
    var staging = newStagedFile(WHASH, "." + WHASH);
    try (var closer = new TaskStack()) {
      
      @SuppressWarnings("resource")
      var ch = new FileOutputStream(staging).getChannel();
      closer.pushClose(ch);
      
      if (crum == null) {
        ChannelUtils.writeRemaining(ch, Constants.DIGEST.sentinelHash());
      
      } else {
        ByteBuffer[] buffers = {
            ByteBuffer.wrap(crum.witnessHash()),
            crum.serialForm()
        };
        ChannelUtils.writeRemaining(ch, buffers);
      }
      
      ch.close();
      
      return moveStaged(staging, whashFile());
    
    } catch (IOException iox) {
      var nx = new NotaryException(
          "on writing " + crum +
          ", detail: " + iox.getMessage(), iox);
      // do not log.fatal(nx);
      throw nx;
    }
  }
  
  
  public final static int WHASH_LEN =
      Constants.HASH_WIDTH + Crum.DATA_SIZE;
  
  
  
  
  public final File dir() {
    return dir;
  }
  
  
  // TODO: this should all be in ONE file, not 2
  public final boolean isCommitted() {
    return mrklFile().exists() || whashFile().exists();
  }
  
  
  protected File mrklFile() {
    return new File(dir, MRKL);
  }
  
  
  protected File whashFile() {
    return new File(dir, WHASH);
  }
  
  
  
  public final long blockNo() {
    return blockNo;
  }
  
  
  /**
   * Creates and returns a new empty file in the staging directory.
   * The file name is of the form <em>RandomId_+hash+ext</em>
   * (sans the <em>+</em>'s). A failure here is considered fatal.
   * 
   * <h4>Concurrency</h4>
   * <p>
   * This is designed to be safe under concurrent access
   * both in the same JVM and across <em>multiple</em> VMs.
   * </p>
   * <h5>Multi-process safety</h5>
   * <p>
   * Cross-process collisions are avoided by naming every staged
   * file with a process-specific random ID.
   * </p>
   * <h5>Thread safey</h5>
   * <p>
   * If no 2 {@code hash} args are the same (under concurrent access),
   * then there are no races in naming staging files. It's the caller's
   * responsibility to ensure that condition is an invariant (not
   * enforced in this class).
   * </p>
   * 
   * @param hash  as hex string
   * @param ext   the file extension (include the dot)
   */
  protected File newStagedFile(String hash, String ext)
      throws NotaryException {
    
    File staged = new File(stagingDir, RandomId.RUN_INSTANCE + "_" + hash + ext);
    
    try {
      if (!staged.createNewFile()) {
        if (staged.isFile() && staged.length() == 0) {
          log.warning(
              "staged file " + staged +
              " already exists; accepting as new cuz empty");
        } else {
          var nx = new NotaryException(
              "failed to create staging file " + stagingDir);
          log.fatal(nx);
          throw nx;
        }
      }
    } catch (IOException iox) {
      var nx = new NotaryException(
          "failed to create staging file " + stagingDir +
          ", detail: " + iox.getMessage(), iox);
      log.fatal(nx);
      throw nx;
    }
    return staged;
  }
  

  /**
   * Attempts to move (rename) the given {@code staged} file and returns
   * {@code true} on success. If it the move fails because the {@code target}
   * file already exists, then the staging file is <em>deleted</em>.
   * 
   * @throws NotaryException
   *    if the move fails <em>and</em> the {@code target} file
   *    does <em>not</em> exist.
   */
  protected boolean moveStaged(File staged, File target)
      throws NotaryException {
    
    final boolean moved = staged.renameTo(target);
    
    if (!moved) {
      if (target.exists()) {
        log.warning("[RACE]: detected on moving " + staged + " to " + target);
        if (!staged.delete())
          log.warning(
              "and adding insult, failed to delete staged file " +
              staged + " :/");
      } else {
        var nx = new NotaryException(
            "failed to move " + stagingDir + " to " + target);
        log.fatal(nx);
        throw nx;
      }
    }
    
    return moved;
  }
  
  
  
  public final Crum addCrum(FreshCrum crum) {
    if (chainParams.blockNoForUtc(crum.utc()) != blockNo)
      throw new IllegalArgumentException(
          "crum UTC block no. mismatch\n" +
          "crum: " + crum + "\n" +
          "crum block no.: " + chainParams.blockNoForUtc(crum.utc()) + "\n" +
          "expected block no.: " + blockNo);
    
    
    final String hexHash = crum.hashHex();
    {
      Crum existing = findHexTreeCrum(hexHash, crum.hash());
      if (existing != null) {
        logRace(existing, crum);
        return existing;
      }
    }
    
    try (TaskStack closer = new TaskStack()) {
      
      // write to the staged file..
      
      File staged = newStagedFile(hexHash, CRUM_EXT);
      
      // the filepath encodes the crum's hash;
      // so the only remaining info to write is the crum's UTC..
      ByteBuffer buffer = crum.utcBuffer();

      @SuppressWarnings("resource")
      FileChannel ch = new FileOutputStream(staged).getChannel();
      closer.pushClose(ch);
      ChannelUtils.writeRemaining(ch, buffer);
      
      // close the channel now (o.w. the move below will fail)
      ch.close();
      
      // ask the crums hex tree where this file belongs
      File cref = crumsHexTree.suggest(hexHash);

      // move it there
      boolean moved = moveStaged(staged, cref);
      
      if (moved)  // GOOD
        return crum;
      
      // woops, there was a race..
      
      // if the move failed, another thread of execution
      // must have succeeded (the race was logged);
      // return the crum that beat us to it..
      
      Crum existing = findHexTreeCrum(hexHash, crum.hash());
      assert existing != null;
      logRace(existing, crum);
      
      return existing;
      
    } catch (IOException iox) {
      var nx = new NotaryException(
          "failed to put " + crum + " to cargo block [" + blockNo + "]: " +
          iox.getMessage(), iox);
      log.fatal(nx);
      throw nx;
    }
  }
  
  
  private void logRace(Crum existing, FreshCrum crum) {
    String warning = "[RACE]: ";
    if (existing.utc() > crum.utc())
      warning += "later ";
    else if (existing.utc() == crum.utc())
      warning += "same ";
    else
      warning += "earler ";
    warning +=
        "crum UTC (" + existing.utc() +
        ") committed by another thread/process beated " + crum +
        " (ignored)";
    
    log.warning(warning);
  }
  
  
  public CargoProof findCargoProof(ByteBuffer hash) {
    
    File mrklFile = mrklFile();
    if (!mrklFile.exists())
      return null;
    final long bytes = mrklFile.length();
    
    try (var closer = new TaskStack()) {
      
      CrumMerkleTree mrklTree;
      if (loadMrklInMemory(bytes)) {
        var buffer = FileUtils.loadFileToMemory(mrklFile);
        mrklTree = new CrumTreeBuffer(buffer);
      } else {
        var ctf = new CrumTreeFile(mrklFile);
        closer.pushClose(ctf);
        mrklTree = ctf;
      }
      
      return mrklTree.findProof(hash);
      
    } catch (TimeChainException tcx) {
      throw tcx;
    } catch (Exception x) {
      var nx = new NotaryException(
          "on loading crum merkle tree [" + blockNo +
          "], detail: " + x.getMessage(), x);
      log.fatal(nx);
      throw nx;
    }
  }
  
  
  
  
  /**
   * Returns the cargo hash. Must already be written.
   */
  private CargoHash cargoHash() throws NotaryException {
    
    try (var closer = new TaskStack()) {

      ByteBuffer hash;
      int cc;

      File mrklFile = mrklFile();
      if (mrklFile.exists()) {
        var tree = new CrumTreeFile(mrklFile);
        closer.pushClose(tree);
        hash = ByteBuffer.wrap(tree.hash());
        cc = tree.idx().count();
      } else {
        File whashFile = whashFile();
        if (!whashFile.exists())
          throw new NotaryException(
            "failed to find cargo hash file in cargo block [" +
            blockNo() + "]");
        
        final long len = whashFile.length();
        if (len == Constants.HASH_WIDTH) {
          hash = Constants.DIGEST.sentinelHash();
          cc = 0;
        } else if (len == WHASH_LEN) {
          hash =
            FileUtils.loadFileToMemory(whashFile).limit(Constants.HASH_WIDTH);
          cc = 1;
        } else {
          throw new NotaryException(
            "illegal file length for " + whashFile + ": " + whashFile.length());
        }
      } 

      return new CargoHash(hash, cc);

    } catch (TimeChainException tcx) {
      throw tcx;
    } catch (Exception x) {
      
      var nx = new NotaryException(
          "on reading cargo hash in block [" + blockNo +
          "], detail: " + x.getMessage(), x);
      
      log.fatal(nx);
      throw nx;
    }
  }
  
  
  public Crum findLoneCommit() {
    
    File whashFile = whashFile();
    if (!whashFile.exists())
      return null;
    
    var buffer = ByteBuffer.allocate(WHASH_LEN);
    try (var closer = new TaskStack()) {
      
      var ch = Opening.READ_ONLY.openChannel(whashFile);
      closer.pushClose(ch);
      
      ChannelUtils.readRemaining(ch, buffer).flip();
      
      if (buffer.remaining() == Constants.HASH_WIDTH) {
        if (!buffer.equals(Constants.DIGEST.sentinelHash())) {
          log.warning("expected sentinel hash not found in " + whashFile);
        }
        return null;
      } else if (buffer.remaining() < WHASH_LEN)
        throw new IOException(
            "too few bytes in " + whashFile + " (" +
            buffer.remaining() + " bytes)");
      
      return new Crum(buffer.position(Constants.HASH_WIDTH));
    
    } catch (NotaryException nx) {
      throw nx;
    } catch (Exception x) {
      var nx = new NotaryException(
          "on loading " + whashFile + ", detail: " + x.getMessage(),
          x);
      log.fatal(nx);
      throw nx;
    }
    
  }
  
  public final static int MAX_MERKLE_MEM = 64 * 1024;
  
  protected boolean loadMrklInMemory(long bytes) {
    return bytes <= MAX_MERKLE_MEM;
  }
  



  public final Crum findHexTreeCrum(ByteBuffer hash) {
    return findHexTreeCrum(IntegralStrings.toHex(hash), hash);
  }
  
  
  private Crum findHexTreeCrum(String hex, ByteBuffer hash) {
    if (hex.length() != Constants.HASH_WIDTH * 2)
      throw new IllegalArgumentException("hash: " + hex);

    File crumFile = crumsHexTree.find(hex);
    if (crumFile == null)
      return null;
    
    long utc = utcAtHexPath(crumFile);
    
    return new Crum(hash, utc);
  }
  
  /**
   * Asserts the {@code utc} read from the given {@code file}
   * falls in the range of this block no.
   * 
   * @param utc   the UTC read from the file (earlier)
   * @param file  informational (neither opened, nor checked)
   * 
   * @return {@code utc}
   */
  protected final long assertUtcInBlock(long utc, File file) {
    
    if (utc < chainParams.inceptionUtc() ||
        chainParams.blockNoForUtc(utc) != blockNo) {
      
      File moved = moveBadFile(file);
      
      var msg = "UTC (" + utc + ") read from " + file +
          " is out-of-bounds in cargo block [" + blockNo + "]; ";
      if (moved != file)
        msg += "moved to " + moved;
      else
        msg += "move failed";
      
      var panic = new NotaryFileException(msg);
      log.fatal(panic);
      
      throw panic;
    }
    
    return utc;
  }
  
  
  /**
   * Attempts to move the given {@code bad} file out of the way,
   * and if successful, returns its new path; otherwise, the
   * input argument is returned.
   * 
   * @see #badFileTargetPath(File)
   */
  protected final File moveBadFile(File bad) {
    File target = badFileTargetPath(bad);
    
    boolean moved = bad.renameTo(target);
    if (!moved) {
      if (target.exists()) {
        log.warning("[RACE]: bad file " + bad + " already moved to " + target);
        return target;
      } else {
        log.warning("failed to moved bad file " + bad + " to " + target);
        return bad;
      }
    }
    
    return target;
  }
  
  
  /**
   * Returns the path the given {@code bad} file should be moved to.
   * <em>Must not create the returned file.</em>
   * 
   * @see #moveBadFile(File)
   */
  protected File badFileTargetPath(File bad) {
    return new File(
        bad.getParentFile(),
        bad.getName() + BAD_FILE_EXT);
  }

}











