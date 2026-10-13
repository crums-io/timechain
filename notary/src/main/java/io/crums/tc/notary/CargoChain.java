/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import static io.crums.tc.Constants.HASH_WIDTH;
import static io.crums.tc.notary.NotaryConstants.CARGO_BLOCK_EXT;
import static io.crums.tc.notary.NotaryConstants.GRAVEYARD_DIR;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.crums.util.tree.fs.DirectoryRemover;
import io.crums.util.io.FileUtils;
import io.crums.tc.BlockProof;
import io.crums.tc.CargoProof;
import io.crums.tc.ChainParams;
import io.crums.tc.Crum;
import io.crums.tc.Crumtrail;
import io.crums.tc.NotaryPolicy;
import io.crums.tc.Receipt;
import io.crums.tc.TimeBinner;
import io.crums.tc.TimeChain;
import io.crums.tc.except.TimeChainException;
import io.crums.tc.notary.except.NotaryException;
import io.crums.util.collections.Lists;
import io.crums.util.Strings;

/**
 * The cargo chain is like an assembly line in a factory. At the head
 * of the assembly line, new cargo blocks are created and fresh crums
 * (witnessed hash-UTC tuples) are deposited in the yet unbuilt cargo
 * block. After wall time has elapsed by a safe margin, the cargo block
 * is built and its computed hash is committed to the timechain block
 * with the same block no.
 * <p>
 * At the tail end of the assembly line, the oldest cargo blocks fall into
 * the dumpster (they are purged). By contract, committed cargo blocks live for
 * the duration of {@linkplain NotaryPolicy#blocksRetained()}-many
 * timechain blocks, during which time a user can retrieve a committed
 * crumtrail.
 * </p>
 */
public class CargoChain implements Channel {

  
  public final static TimeBinner FINEST_BINNER = TimeBinner.MILLIS_32;
  public final static int MIN_BINNER_EXP = FINEST_BINNER.binExponent();
  
  
  /**
   * @see CargoChain#CargoChain(InitArgs)
   */
  public record InitArgs(
      TimeChain timechain,
      NotarySettings settings,
      File dir,
      NotaryLog log,
      NotaryLog blockLog) {
    
    public InitArgs {
      if (!timechain.params().equals(settings.chainParams()))
        throw new IllegalArgumentException(
            "timechain / settings mismatch: " +
            timechain.params() + " / " + settings.chainParams());
      if (timechain.params().timeBinner().binExponent() < MIN_BINNER_EXP)
        throw new IllegalArgumentException(
            "timechain block duration ambitiously too short: " +
            timechain.params().timeBinner() + " (exponent: " +
            timechain.params().timeBinner().binExponent() +
            "); minimum is " + TimeBinner.forExponent(MIN_BINNER_EXP) +
            " (exponent: " + MIN_BINNER_EXP + ")");
      
      Objects.requireNonNull(dir);
      Objects.requireNonNull(log);
      Objects.requireNonNull(blockLog);
      
      if (!timechain.isOpen())
        throw new IllegalArgumentException(
            "chain is closed: " + timechain);
    }
    
    public InitArgs(
        TimeChain timechain,
        NotarySettings policy,
        File dir,
        NotaryLog log) {
      this(timechain, policy, dir, log, log);
    }
  }
  
  
  protected final static FilenameFilter CARGO_BLOCK_FILTER =
      new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
          return name.endsWith(CARGO_BLOCK_EXT);
        }
      };
      
  
  

      
      
      
      
      
      
      
      
      
      
      
  private final TimeChain timechain;
  private final NotarySettings settings;
  private final ChainParams chainParams;
  private final File dir;
  private final NotaryLog log;
  private final NotaryLog blockLog;

  /**
   * @see InitArgs
   */
  public CargoChain(InitArgs args) {
    this.timechain = args.timechain();
    this.settings = args.settings();
    this.chainParams = timechain.params();
    this.dir = args.dir();
    try {
      FileUtils.ensureDir(dir);
    } catch (Exception x) {
      var nx = new NotaryException(
          "on  <init>(): " + x.getMessage(),
          x);
      args.log().fatal(nx);
      throw nx;
    }
    this.log = args.log();
    this.blockLog = args.blockLog();
  }
  
  
  /** Copy / promotion constructor. */
  protected CargoChain(CargoChain copy) {
    this.timechain = copy.timechain;
    this.settings = copy.settings;
    this.chainParams = copy.chainParams;
    this.dir = copy.dir;
    this.log = copy.log;
    this.blockLog = copy.blockLog;
  }
  
  
  
  public NotaryLog log() {
    return log;
  }
  
  
  
  public TimeChain timechain() {
    return timechain;
  }
  
  
  
  public NotarySettings settings() {
    return settings;
  }
  
  
  
  @Override
  public boolean isOpen() {
    return timechain.isOpen();
  }



  @Override
  public void close() {
    timechain.close();
  }




  private final static int CB_EXT_LEN = CARGO_BLOCK_EXT.length();
  
  
  /** Block no., directory name tuple. */
  protected record BlockDir(long blockNo, String dirname)
      implements Comparable<BlockDir> {
    
    /**
     * {@code blockNo} is inferred from the {@code dir}
     * name.
     */
    public BlockDir(String dirname) throws NumberFormatException {
      this(
          Long.parseLong(
              dirname.substring(
                  0, dirname.length() - CB_EXT_LEN)), dirname);
    }

    @Override
    public int compareTo(BlockDir o) {
      long diff = blockNo - o.blockNo;
      return diff < 0 ? -1 : (diff == 0 ? 0 : 1);
    }
    
    public File toFile(File dir) {
      return new File(dir, dirname);
    }
  }
  
  
  public Receipt addCrum(FreshCrum crum) {
    Crum out = getBlockForWrite(crum).addCrum(crum);
    return new Receipt(chainParams, out);
  }
  
  
  
  protected final CargoBlock getBlockForWrite(FreshCrum crum) {
    
    final long crumBlockNo = chainParams.blockNoForUtc(crum.utc());
    var blocks = activeBlocksLazy();
    
    if (blocks.isEmpty())
      return createNewBlock(crumBlockNo);
    

    final CargoBlock last = blocks.get(blocks.size() - 1);
    
    final long diff = crumBlockNo - last.blockNo();
    
    if (diff == 0)
      return last;
    
    if (diff == 1) 
      return createNewBlock(crumBlockNo);
      
    if (diff > 1) {
      log.info(
          "skipping " + Strings.nOf(diff - 1, "cargo block") +
          " (to block [" + crumBlockNo + "])");
      return createNewBlock(crumBlockNo);
    }
    
    
    if (diff == -1) {
      
      // this can be an edge case race condition when
      // multiple threads of execution(1) are accessing
      // (the file system). Usually, that's OK..
      
      // but make sure the current "thread"(2) has not "paused" 
      // for too long (since when the FreshCrum was 1st created)
      //
      // (1) threads or processes
      // (2) in quotes cuz it's the same w/ virtual threads
      //
      final long now = System.currentTimeMillis();
      final long utcDiff = now - crum.utc();
      if (utcDiff > settings.maxConcurrentLag()) {
        
        int maxLag = settings.maxConcurrentLag();
        var error = new AssertionError(
            "maxConcurrentLag (" + maxLag +
            ") breached by " + (utcDiff - maxLag) + " millis: " +
            crum);
        log.fatal(error);
        throw error;
      }
      
      // check for cross machine time skews..
      
      final long blockTimeSkew = now -
          settings.chainParams().utcForBlockNo(crumBlockNo + 1);
      
      if (blockTimeSkew < 0 &&
          -blockTimeSkew > settings.maxCrossMachineTimeSkew()) {
        
        // NOTE: do realize we might be killing a good daemon
        // with the clock leader being the bad one, leaving
        // all good fellows dead and only the bad one alive.
        // (Since we can't kill the bad process from here,
        // the best we can do is not participate)
        
        var error = new AssertionError(
            "Cross-machine time skew detected: block [" + (crumBlockNo + 1) +
            "] is ahead of local system time by " + blockTimeSkew +
            " milliseconds; maximum allowed is " +
            settings.maxCrossMachineTimeSkew());
        
        log.fatal(error);
        throw error;
      }
      
      // alright, we'll use the cargo block 2nd from the 
      // frontier block (whether one exists or not):
      // remember, the blocks are firstly conceptual (modeled by block no.),
      // and then secondly, manifested as directories
      
      int count = blocks.size();
      if (count > 1) {
        var nextToLast = blocks.get(count - 1);
        return nextToLast.blockNo() == crumBlockNo ?
            nextToLast : createNewBlock(crumBlockNo);
      }
      
    } // if (diff == -1)
    
    // diff < -1
    
    // TODO:
    // REVIEW: why does this even matter? It should only matter if
    // -diff is large (and increasing); o.w. all these assertion errors
    // are wrong! They should be ordinary Runtime exceptions (if at all), not
    // VM-ending errors! 
    // 
    var e = new AssertionError(
        "tail block [" + (crumBlockNo - diff) + "] ahead of " +
        crum + " block [" + crumBlockNo + "] by " + -diff + " blocks");
    
    log.fatal(e);
    throw e;
    
  }
  
  
  private File blockDirFile(long blockNo) {
    return new File(dir, Long.toString(blockNo) + CARGO_BLOCK_EXT);
  }
  
  
  private CargoBlock createNewBlock(long blockNo) {
    return getCargoBlock(blockNo, false);
  }
  
  
  private CargoBlock getBlockIfPresent(long blockNo, long commitNo) {
    
    return
        commitNo - blockNo > settings.blocksRetained() ?
            null : getCargoBlock(blockNo, true);
  }
  
  
  /**
   * Conditonally creates and returns a new {@code CargoBlock} instance.
   * If {@code readOnly}, and the underlying cargo block directory does
   * not exist, then {@code null} is returned. Otherwise, an instance
   * is always created (the underlying block directory is created on demand).
   * 
   * @param blockNo   &ge; 1
   * @param readOnly  if {@code true}, and the block directory
   *                  does not exist, then {@code null} is returned
   *                  
   * @return not {@code null}, if {@code readOnly} is {@code false}
   */
  protected CargoBlock getCargoBlock(long blockNo, boolean readOnly) {
    File cbDir = blockDirFile(blockNo);
    if (readOnly && !cbDir.exists())
      return null;
    
    var args = new CargoBlock.InitArgs(
        settings, blockNo, cbDir, blockLog, readOnly);
    return new CargoBlock(args);
  }
  
  
  
  /**
   * Searches for a receipt of the given {@code hash} in the last
   * {@linkplain NotaryPolicy#blocksSearched() blocks-searched}
   * number of (logical) cargo blocks. The search is performed
   * in reverse order of block no. The highest block no.
   * searched is discovered from the existing directories on the
   * file system; the descending block no.s that follow on the
   * search path, are determined by block no. (the logical block),
   * not by existing cargo directories (whose block no.s may contain
   * gaps.)
   */
  public Optional<Receipt> findReceipt(ByteBuffer hash, long fromBlockNo) {

    assertFromBlockNoPositive(fromBlockNo);
    if (hash.remaining() != HASH_WIDTH)
      throw new IllegalArgumentException("wrong hash width: " + hash);
    
    var activeDirs = activeBlockDirs();
    final int size = activeDirs.size();
    if (size == 0)
      return Optional.empty();
    
    final long stopCbNo =
        activeDirs.get(size - 1).blockNo() - settings.blocksSearched();
    final long commitNo = timechain.size();
    
    for (int index = size; index-- > 0; ) {
      var bd = activeDirs.get(index);
      if (bd.blockNo() == stopCbNo)
        break;
      var block = toCargoBlock(bd);
      var receipt = findReceipt(hash, block, commitNo, fromBlockNo);
      if (receipt != null)
        return Optional.of(receipt);
    }
    
    
    return Optional.empty();
  }
  
 
  private void assertFromBlockNoPositive(long fromBlockNo) {
    if (fromBlockNo <= 0)
      throw new IllegalArgumentException(
        "fromBlockNo must be positive; " + fromBlockNo);
  }
  
  
  /**
   * Returns a receipt from the given {@code block}, if any; {@code null}, o.w.
   * 
   * @return  {@code null}, if not found
   */
  private Receipt findReceipt(
      ByteBuffer hash, CargoBlock block, long commitNo, long fromBlockNo) {
    
    if (fromBlockNo > block.blockNo())
      throw new IllegalArgumentException(
        "fromBlockNo " + fromBlockNo + " > cargo block no. " + block.blockNo());
    
    
    if (block.blockNo() <= commitNo) {
      
      BlockProof blockProof;
      {
        Long[] blockNos;
        if (fromBlockNo == block.blockNo())
          blockNos = commitNo == fromBlockNo ?
              new Long[] { fromBlockNo } :
              new Long[] { fromBlockNo, commitNo };
        else
          blockNos = commitNo == block.blockNo() ?
              new Long[] { fromBlockNo, commitNo } :
              new Long[] { fromBlockNo, block.blockNo(), commitNo };
  
        blockProof = timechain.stateProof(false, blockNos);
      }
      

      switch (block.state()) {
      case MRKL:
        {
          CargoProof cargoProof = block.findCargoProof(hash);
          if (cargoProof != null) {
            // var blockProof = timechain.getBlockProof(block.blockNo());

            var crumtrail = Crumtrail.newMerkleTrail(blockProof, cargoProof);
            return new Receipt(crumtrail);
            
          }
          return null;
        }
      case LONE:
        Crum crum = block.findLoneCommit();
        if (crum != null && crum.hash().equals(hash)) {
          // var blockProof = timechain.getBlockProof(block.blockNo());
          var crumtrail = Crumtrail.newLoneTrail(blockProof, crum);
          return new Receipt(crumtrail);
        }
        return null;
      case UNBUILT:

        var error = new AssertionError(
            "expected commit file not found cargo block [" + block.blockNo() +
            "]; chain commit at [" + commitNo + "]");
        
        log.fatal(error);
        throw error;
      }
      
    }
    
    Crum crum = block.findHexTreeCrum(hash);
    return crum == null ? null : new Receipt(chainParams, crum);
  }
  
  
  
  /**
   * Finds and returns the receipt for the given {@code crum}.
   * If the given crum is indeed recorded in its appropriate
   * block (determined by the crum's {@linkplain Crum#utc() utc})
   * and if the block is still retained
   * ({@linkplain NotaryPolicy#blocksRetained()}), then a
   * receipt shall be found; otherwise, "empty" is returned.
   * <p>
   * This method searches at most one cargo block.
   * </p>
   */
  public Optional<Receipt> findCrumReceipt(Crum crum, long fromBlockNo) {
    
    assertFromBlockNoPositive(fromBlockNo);

    final long blockNo = chainParams.blockNoForUtcUnchecked(crum.utc());
    if (blockNo <= 0)
      return Optional.empty();
    
    final long commitNo = timechain.size();
    
    
    var cargoBlock = getBlockIfPresent(blockNo, commitNo);
    if (cargoBlock != null) {
      var rcpt = findReceipt(crum.hash(), cargoBlock, commitNo, fromBlockNo);
      if (rcpt != null)
        return Optional.of(rcpt);
    }

    return Optional.empty();
  }
  
  
  /**
   * Returns the graveyard directory (where to-be-purged cargo directories are first
   * moved to).
   */
  public File graveyardDir() {
    File graveyard = new File(dir, GRAVEYARD_DIR);
    FileUtils.ensureDir(graveyard);
    return graveyard;
  }


  /**
   * Sweeps clean (deletes) the contents of the {@linkplain #graveyardDir()
   * graveyard directory} and returns the number objects (files and directories)
   * deleted. This may be useful on startup.
   */
  public int sweepGraveyard() {
    int tally = 0;
    for (File file : graveyardDir().listFiles()) {
      int count = DirectoryRemover.removeTree(file);
      if (count < 0) {
        log.warning("[PURGE]: failed to complete sweep; " + file);
        tally += -count;
      } else
        tally += count;
    }
    return tally;
  }
  
  
  
  
  
  
  public final static int GRACE_BLOCKS = 1;
  
  /**
   * Purges the inactive blocks and returns the number
   * of blocks purged.
   * 
   * @return count of blocks purged
   */
  public int purgeInactiveBlocks() {
    
    final long commitNo = timechain.size();
    final long lastPurgableNo =
        commitNo - settings.blocksRetained() - GRACE_BLOCKS;
    
    if (lastPurgableNo <= 0)
      return 0;
    
    var all = sortedBlockDirs();
    
    if (all.isEmpty()) {
      log.warning("no cargo block directories; commit no.: " + commitNo);
      return 0;
    }
    
    
    final int indexOfLastPurgable; // (exc)
    {
      int index =
          Collections.binarySearch(
              Lists.map(all, BlockDir::blockNo),
              lastPurgableNo);
      indexOfLastPurgable = index >= 0 ? index + 1 : -1 - index;
    }

    if (indexOfLastPurgable == 0)
      return 0;
    
    var purgableBlockDirs = all.subList(0, indexOfLastPurgable);
    
    File graveyard = graveyardDir();
    
    int tally = 0;
    int errors = 0;
    for (var bd : purgableBlockDirs) {
      File plot = new File(graveyard, bd.dirname());
      File deadDir = bd.toFile(dir);
      boolean moved = deadDir.renameTo(plot);
      if (!moved) {
        var msg =
            "[RACE]: purge failed to move cargo block [" + bd.blockNo() +
            "] to graveyard in possible race: ";
        
        if (deadDir.exists())
          msg += "block still exists; ";
        else
          msg += "block no longer exists; ";

        boolean plotExists = plot.exists();
        if (plotExists) {
          msg += "plot exists (proceeding with purge)";
        } else
          msg += "plot does not exist";
        log.warning(msg);
        
        if (!plotExists) {
          ++errors;
          continue;
        }
      }
      int rmCount = DirectoryRemover.removeTree(plot);
      if (rmCount < 0) {
        log.warning(
            "[RACE]: failed to complete purge of cargo block dir [" + bd.blockNo() +
            "]; " + Strings.nOf(-rmCount, "object") + " removed");
        ++errors;
      } else {
        ++tally;
        log.info(
            "[PURGE]: cargo block [" + bd.blockNo() + "] removed (" +
            Strings.nOf(rmCount, "object") + ")");
      }
    }
    

    if (tally > 1 || errors > 0)
      log.info(
          "         " + tally + " cargo blocks removed; " +
          Strings.nOf(errors, "error"));
    
    
    return tally;
  }
  
  
  /**
   * Builds the committable cargo blocks, commits their hashes to the
   * time chain and returns a tally of the crums added.
   * 
   * @return no. of crums added. A reporting statistic,
   *         not directly used in logic anywhere
   */
  public int buildAndCommit() {
    try {
      // note the current "commit" block no.
      final long lastCommitNo = timechain.size();
      
      final long now = System.currentTimeMillis();
      
      var bds = sortedBlockDirs();
      final int startIndex; // (inc)
      final int endIndex;   // (exc)
      {
        // find the start index..
        final int cindex = Collections.binarySearch(
            Lists.map(bds, BlockDir::blockNo),
            lastCommitNo);
        
        if (cindex < 0) {
          int insertIndex = -1 - cindex;
          if (insertIndex == bds.size())  // (true if bds.isEmpty)
            return 0;
          startIndex = insertIndex;
        } else {
          startIndex = cindex + 1;
        }
        
        // find the end index..
        final long commitMillisPad =
            settings.blockCommitLag() + 1;

        int iend = bds.size();
        for (int index = iend; index-- > 0; ) { // at least once
          long nextBlockUtc =
              chainParams.utcForBlockNo(
                  bds.get(index).blockNo() + 1);
          if (nextBlockUtc + commitMillisPad <= now)
            break;
          iend = index;
        }
        endIndex = iend;
      }
      
      if (startIndex == endIndex)
        return 0;
      
      var buildDirs = bds.subList(startIndex, endIndex);
      if (buildDirs.size() >= settings.blocksRetained()) {
        log.warning(
            "unbuilt cargo block queue length (" + buildDirs.size() +
            " blocks) is breaching blocks-retained setting (" +
            settings.blocksRetained() + ")");
      }
      int tally = 0;
      for (var bd : buildDirs) {
        var block = toCargoBlock(bd);
        var cargoHash = block.buildCargo();
        final long blockNo = block.blockNo();
        long blocksAdded = timechain.recordBlockNo(blockNo, cargoHash.hash());
        
        if (blocksAdded <= 0) {
          log.info(
              "block [" + blockNo + "] already commited: commit no. [" +
              (blockNo - blocksAdded) + "]");
        
        } else {

          tally += cargoHash.crums();
          log.info(
              "block [" + blockNo + "] committed (" +
              Strings.nOf(cargoHash.crums(), "crum") + ")");
        }
      }
      
      return tally;
      
    } catch (TimeChainException tcx) {
      throw tcx;
    } catch (Exception x) {
      throw new NotaryException(x);
    }
  }
  
  
  
  
  
  
  
  
  
  protected final List<CargoBlock> activeBlocksLazy() {
    return Lists.map(activeBlockDirs(), this::toCargoBlock);
  }
  
  
  
  
  /**
   * Returns the existing cargo block directory names.
   * 
   * @return unsort, non-null array
   */
  protected final String[] listCargoDirNames() {
    var names = dir.list(CARGO_BLOCK_FILTER);
    if (names == null)
      throw new NotaryException(
          "failed to list files in cargo chain directory " +
          dir.getAbsolutePath());
    return names;
  }
  
  
  /**
   * Returns the existing cargo block directories as a sorted
   * list of {@link BlockDir}s. The returned list is <em>not</em>
   * lazily loaded.
   */
  protected final List<BlockDir> sortedBlockDirs() throws NumberFormatException {
    String[] cds = listCargoDirNames();
    if (cds.length == 0)
      return List.of();
    BlockDir[] bds = new BlockDir[cds.length];
    for (int index = cds.length; index-- > 0; )
      bds[index] = new BlockDir(cds[index]);
    Arrays.sort(bds);
    return Lists.asReadOnlyList(bds);
  }
  
  
  /**
   * Returns the tail end of the {@link #sortedBlockDirs()} list,
   * such that the first block no. in the returned sublist, is
   * greater than {@code timechain.size() - settings.blocksRetained()}.
   * (Cargo blocks numbered at or below that cutoff no. are eligible
   * for purge.)
   */
  protected final List<BlockDir> activeBlockDirs() {
    
    final long commitNo = timechain.size();
    var all = sortedBlockDirs();
    
    
    // note the block no. cutoff: no blocks at or below
    // this no. are considered active
    final long blockNoCutoff = commitNo - settings.blocksRetained();
    if (blockNoCutoff <= 0)
      return all;
    
    // no blocks at or below this index are active
    final int cutoffIndex;
    {
      int index =
          Collections.binarySearch(
              Lists.map(all, BlockDir::blockNo),
              blockNoCutoff);
      if (index < 0) {
        int insertIndex = -1 - index;
        if (insertIndex == 0)
          return all;
        // set to one below the insertion point
        cutoffIndex = insertIndex - 1;
      } else
        cutoffIndex = index;
    }

    final int startIndex = cutoffIndex + 1;
    final int size = all.size();
    
    if (startIndex == size) {
      throw new AssertionError(
          "commitNo: " + commitNo + "\n" +
          "blocksRetained: " + settings.blocksRetained() + "\n" +
          "blockNoCutoff:  " + blockNoCutoff + "\n" +
          "cutoffIndex:    " + cutoffIndex + "\n" +
          "block no.s:     " + Lists.map(all, BlockDir::blockNo)
          );
    }
    
    
    return all.subList(startIndex, size);
  }
  
  
  
  protected final CargoBlock toCargoBlock(BlockDir bDir) {
    var args = new CargoBlock.InitArgs(
        settings, bDir.blockNo(), bDir.toFile(dir), blockLog);
    return new CargoBlock(args);
  }
  

}




















