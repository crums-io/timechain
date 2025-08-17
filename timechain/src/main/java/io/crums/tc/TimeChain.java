/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc;


import static io.crums.tc.Constants.DIGEST;
import static io.crums.tc.Constants.HASH_WIDTH;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.util.Objects;

import io.crums.io.Opening;
import io.crums.io.channels.ChannelUtils;
import io.crums.sldg.LevelsPointer;
import io.crums.sldg.Path;
import io.crums.sldg.Row;
import io.crums.sldg.SkipLedger;
import io.crums.tc.except.TimeChainException;
import io.crums.util.Lists;
import io.crums.util.Strings;
import io.crums.util.TaskStack;

/**
 * A time chain backed by a file. The blocks in the time chain model
 * a skip ledger's rows.
 * 
 * @see #recordBlockForUtc(long, ByteBuffer)
 * @see SkipLedger
 * @see SkipBlock
 */
public class TimeChain extends SkipLedger implements Channel {
  
  
  
  public final static byte VERSION = 1;
  public final static int VERSION_BYTES = 1;
  public final static String HEADER_MAGIC = "cRUMStIMEcHAIN ";
  
  private final static ByteBuffer MAGIC_BUFFER = Strings.utf8Buffer(HEADER_MAGIC);
  
  private final static int CHAIN_HEAD_OFF =
      MAGIC_BUFFER.capacity() + VERSION_BYTES;
  
  
  public final static int CHAIN_HEAD_BYTES = 4;
  
  private final static int PARAMS_OFF = CHAIN_HEAD_OFF + CHAIN_HEAD_BYTES;
  public final static int ALIGN_PAD_BYTES = 3;
  
  public final static int PREAMBLE_BYTES =
      MAGIC_BUFFER.capacity() +
      VERSION_BYTES +
      CHAIN_HEAD_BYTES +
      ChainParams.BYTE_SIZE +
      ALIGN_PAD_BYTES;

  public final static int DEFAULT_CHAIN_HEAD = PREAMBLE_BYTES;
  
  
  /**
   * Loads and returns an existing instance in read/write mode.
   * 
   * @param file      existing time chain file
   * @return          {@linkplain #load(File, boolean) load(file, false)}
   */
  public static TimeChain load(File file) throws IOException {
    return load(file, false);
  }
  
  
  /**
   * Loads and returns an existing instance.
   * 
   * @param file      existing time chain file
   * @param readOnly  opening mode
   * 
   * @return an open instance
   */
  public static TimeChain load(File file, boolean readOnly)
      throws IOException {
    
    if (!file.isFile())
      throw new FileNotFoundException(file.toString());

    try (var closeOnFail = new TaskStack()) {
      Opening mode = readOnly ? Opening.READ_ONLY : Opening.READ_WRITE_IF_EXISTS;
      var ch = mode.openChannel(file);
      closeOnFail.pushClose(ch);
      
      final long fLen = ch.size();
      if (fLen < PREAMBLE_BYTES)
        throw new IllegalArgumentException(
            "missing preamble in " + file);

      var buffer = ByteBuffer.allocate(PREAMBLE_BYTES);
      ChannelUtils.readRemaining(ch, 0, buffer).flip();
      
      buffer.limit(MAGIC_BUFFER.capacity());
      if (!MAGIC_BUFFER.equals(buffer)) {
        throw new IllegalArgumentException(
            "magic header missing in " + file);
      }
      
      buffer.clear();
      
      // skipping over version field for now
      
      final int chainHead = buffer.getInt(CHAIN_HEAD_OFF);
      if (chainHead > fLen)
        throw new IllegalArgumentException(
            "chain head offset (" + chainHead +
            ") is beyond file length (" + fLen + ")");
      
      buffer.position(PARAMS_OFF);
      
      var params = ChainParams.load(buffer);
      
      var chain = new TimeChain(file, params, ch, chainHead, readOnly);
      closeOnFail.clear();
      return chain;
    }
  }
  
  

  
  /**
   * Begins a new time chain, written to the given file, and returns
   * an open time chain instance. This operation just writes the
   * time chain header.
   * 
   * @param file        path to new file (must not exist)
   * @param timeBinner  defines the chain's time slots
   * 
   * @return a read/write instance
   */
  public static TimeChain inceptNewChain(
      File file, TimeBinner timeBinner)
      throws IOException {
    
    long now = System.currentTimeMillis();
    return inceptNewChain(file, timeBinner, now);
  }

  
  
  /**
   * Begins a new time chain, written to the given file, and returns
   * an open time chain instance. This operation just writes the
   * time chain header.
   * 
   * @param file        path to new file (must not exist)
   * @param timeBinner  defines the chain's time slots
   * @param startUtc    determines the chain's inception UTC
   * 
   * @return a read/write instance
   */
  public static TimeChain inceptNewChain(
      File file, TimeBinner timeBinner, long startUtc)
      throws IOException {
    
    var params = ChainParams.forStartUtc(timeBinner, startUtc);
    
    try (var closeOnFail = new TaskStack()) {
      var ch = Opening.CREATE.openChannel(file);
      closeOnFail.pushClose(ch);

      var buffer = ByteBuffer.allocate(PREAMBLE_BYTES);
      final int headerSize = DEFAULT_CHAIN_HEAD;
      buffer.put(MAGIC_BUFFER.duplicate())
        .put(VERSION)
        .putInt(headerSize);
      
      params.writeTo(buffer);
      
      assert buffer.remaining() == ALIGN_PAD_BYTES;
      
      buffer.clear();
      ChannelUtils.writeRemaining(ch, 0, buffer);
      ch.force(false);
      
      var chain = new TimeChain(file, params, ch, headerSize, false);
      closeOnFail.clear();
      return chain;
    }
  }
  
  // no null check
  private final File file;
  
  private final ChainParams params;
  private final FileChannel ch;
  private final long headerSize;
  private boolean readOnly;
  
  
  

  /**
   * 
   */
  public TimeChain(
      ChainParams params, File file, int headerSize, boolean readOnly)
      throws IOException {
    this(
        file,
        params,
        (readOnly ?
            Opening.READ_ONLY :
              Opening.READ_WRITE_IF_EXISTS).openChannel(file),
        headerSize,
        readOnly);
  }
  
  
  protected TimeChain(File file, ChainParams params, FileChannel ch, int headerSize, boolean readOnly) {
    this.file = file;
    this.params = Objects.requireNonNull(params, "null params");
    this.ch = Objects.requireNonNull(ch, "null channel");
    this.headerSize = headerSize;
    this.readOnly = readOnly;
    
    if (headerSize < 0)
      throw new IllegalArgumentException("headerSize " + headerSize);
    
    if (!ch.isOpen())
      throw new IllegalArgumentException("file channel closed");
  }
  
  
  
  public File file() {
    return file;
  }
  

  @Override
  public boolean isOpen() {
    return ch.isOpen();
  }

  @Override
  public void close() throws UncheckedIOException {
    try {
      ch.close();
    } catch (IOException iox) {
      throw new UncheckedIOException("on closing " + this, iox);
    }
  }
  
  
  public ChainParams params() {
    return params;
  }
  
  public boolean isReadOnly() {
    return readOnly;
  }


  @Override
  public int hashWidth() {
    return HASH_WIDTH;
  }
  @Override
  public String hashAlgo() {
    return Constants.HASH_ALGO;
  }
  
  
  /**
   * Not supported.
   * 
   * @see #recordBlockForUtc(long, ByteBuffer)
   */
  @Override
  public long appendRows(ByteBuffer entryHashes)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }
  
  /**
   * Not supported.
   */
  @Override
  protected void writeRowsImpl(long startRn, ByteBuffer inputHashes)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }

  /**
   * Not supported.
   */
  @Override
  public long commitRows(long startRn, ByteBuffer inputHashes)
      throws UnsupportedOperationException {
    throw new UnsupportedOperationException();
  }


  /**
   * Records a new block and returns the number of empty blocks
   * appended to the end of the end of the chain before adding
   * this one.
   * 
   * <h4>Performance Note</h4>
   * <p>
   * This method is a bit slow, about 10 ms per invocation.
   * Not unusual for disk I/O, particularly since it flushes
   * to disk before returning. We don't expect blocks to be
   * ever written faster in a real production environmet.
   * Note won't be using this class to "replay" nor validate
   * existing time chains; instead we'll use something like
   * {@linkplain io.crums.sldg.cache.HashFrontier}.
   * </p>
   * <h4>TODO</h4>
   * <p>
   * If there are a lot of intervening empty blocks, it's better
   * to write those and the new block en bloc (sic). That's the
   * only use case I know of where a perf boost would be welcome.
   * (Kicking this can down the road for now.)
   * </p>
   * 
   * @param utc         the block no. is inferred from this
   * @param cargoHash   the hash of the set of crums witnessed
   * 
   * @see #recordBlockNo(long, ByteBuffer)
   */
  public long recordBlockForUtc(long utc, ByteBuffer cargoHash)
      throws IOException {
    long now = System.currentTimeMillis();
    if (utc > now)
      throw new IllegalArgumentException(
          "utc (" + utc + ") > system time (" + now + ")");
    
    long blockNo = params.blockNoForUtc(utc);
    return recordBlockNo(blockNo, cargoHash);
  }
  
  
  /**
   * @see #recordBlockForUtc(long, ByteBuffer)
   */
  public long recordBlockForUtc(long utc, byte[] cargoHash)
      throws IOException {
    return recordBlockForUtc(utc, ByteBuffer.wrap(cargoHash));
  }
  
  
  
  /**
   * Meat of recording time chain blocks. This is
   * more error prone for the user that simply using
   * a "representative" UTC in lieu of a block no.
   * 
   * 
   * @param cargoHash   the hash of the set of crums witnessed
   * 
   * @return {@code blockNo -} {@link #blockCount()}
   * 
   * @see #recordBlockForUtc(long, ByteBuffer)
   */
  public long recordBlockNo(
      long blockNo, ByteBuffer cargoHash)
      throws IOException {
    
    
    final long blockCount = blockCount();
    
    if (blockNo <= blockCount) {
      // we have a race.. another thread/process beat us to it
      // that's ok, but before bailing, verify
      // the 2 writers (us and them) are in agreement
      
      var block = getBlock(blockNo);
      if (!block.cargoHash().equals(cargoHash)) {
        throw new IllegalArgumentException(
            "attempt to overwrite committed cargo hash in block [" +
            blockNo + "]");
      }
      return blockNo - blockCount;
    }
    

    for (long zBlockNo = blockCount; ++zBlockNo < blockNo; )
      writeEmptyBlock(zBlockNo);
    
    writeBlock(blockNo, cargoHash);
    ch.force(false);
    return blockNo - blockCount;
  }
  
  
  private void writeEmptyBlock(long blockNo) throws IOException {
    writeBlock(blockNo, DIGEST.sentinelHash());
  }
  
  private void writeBlock(long blockNo, ByteBuffer cargoHash)
      throws IOException {
    
    Block block = new BuildBlock(blockNo, cargoHash).toBlock();
    long offset = blockOffset(blockNo);
    ChannelUtils.writeRemaining(ch, offset, block.serialize());
  }
  
  
  
  
  
  
  /**
   * Returns the block count.
   */
  public long blockCount() throws IOException {
    return (ch.size() - headerSize) / Block.BYTE_SIZE;
  }

  
  @Override
  public long size() throws TimeChainException {
    try {
      return blockCount();
    
    } catch (IOException iox) {
      throw new TimeChainException("on reading file length", iox);
    }
  }
  
  
  private long blockOffset(long blockNo) {
    long blockIndex = blockNo - 1;
    return blockIndex * Block.BYTE_SIZE + headerSize;
  }
  
  
  
  /**
   * Returns a block proof linking the first block to the last.
   * 
   * @throws IllegalArgumentException
   *      if nothing is committed to the chain (i.e. empty). Since this is
   *      an edge case, and then only immediately following the chain's
   *      inception, it is only <em>documented, but not corrected</em>
   *      (wrong type of exception: no argument, after all).
   */
  public BlockProof getStateProof() {
    return stateProof(true, 1L);
  }
  
  
  public BlockProof getBlockProof(long target) {
    Long[] targets =
      target == 1L ?
        new Long[] { 1L } : new Long[] { 1L, target };
    return stateProof(true, targets);
  }
  




  public BlockProof stateProof(boolean hi, Long... blockNos) {
    final int count = blockNos.length;
    if (count == 0)
      throw new IllegalArgumentException("empty blockNos");
    Long[] targets;
    if (hi) {
      long lastBlockNo = size();
      long lastTarget = blockNos[count - 1];
      if (lastTarget >= lastBlockNo)
        targets = blockNos;
      else {
        targets = new Long[count + 1];
        targets[count] = lastBlockNo;
        for (int index = count; index-- > 0; )
          targets[index] = blockNos[index];
      }
    } else
      targets = blockNos;
    
    // (path implementation is lazy.. so we prefetch it)
    Path path = getPath(targets).pack().path();
    
    return new BlockProof(params, path);
  }
  
  
  
  /**
   * Returns the block at the specified no.
   * 
   * @param blockNo 1 &le; {@code blockNo} &le; {@linkplain #blockCount()}
   * @return
   * @throws IllegalArgumentException
   *         if {@code blockNo} is out-of-bounds
   */
  public SkipBlock getBlock(long blockNo) throws IOException {
    checkRealRowNumber(blockNo);
    Objects.checkIndex(blockNo - 1, blockCount());
    long offset = blockOffset(blockNo);
    ByteBuffer buffer = ByteBuffer.allocate(Block.BYTE_SIZE);
    ChannelUtils.readRemaining(ch, offset, buffer).flip();
    Block block = new Block(buffer, false);
    return new ChainBlock(blockNo, block);
  }
  
  
  public SkipBlock getBlockForUtc(long utc) throws IOException {
    long blockNo = params.blockNoForUtc(utc);
    return getBlock(blockNo);
  }
  

  @Override
  public SkipBlock getRow(long blockNo) {
    try {
      return getBlock(blockNo);
    
    } catch (IOException iox) {
      throw new UncheckedIOException(
          "on reading block [" + blockNo + "]: " + iox, iox);
    }
  }
  

  @Override
  public ByteBuffer rowHash(long blockNo) {
    if (blockNo == 0)
      return DIGEST.sentinelHash();
    try {
      Objects.checkIndex(blockNo - 1, blockCount());
      long offset = blockOffset(blockNo);
      ByteBuffer buffer = ByteBuffer.allocate(HASH_WIDTH);
      
      return
          ChannelUtils.readRemaining(ch, offset, buffer)
          .flip()
          .asReadOnlyBuffer();
      
    } catch (IOException iox) {
      throw new UncheckedIOException(
          "on reading hash for block [" + blockNo + "]: " + iox, iox);
    }
  }
  
  
  private ByteBuffer prevBlockHash(long blockNo, int level) {
    Objects.checkIndex(level, skipCount(blockNo));
    long refBlockNo = blockNo - (1L << level);
    return rowHash(refBlockNo);
  }
  
  
  /**
   * A block in a time chain. It models a skip ledger row.
   * (It <em>is</em> a skip ledger row, actually.) Each block
   * takes 68 bytes on disk.
   */
  public abstract class SkipBlock extends Row {

    final long blockNo;

    SkipBlock(long blockNo) {
      this.blockNo = blockNo;
      assert blockNo > 0;
    }

    /**
     * Returns the block no. Synonym for row number in skip ledger terms.
     * 
     * @return positive
     */
    public final long blockNo() {
      return blockNo;
    }
    
    
    
    /**
     * Returns the cargo hash. Synonym for input hash in skip ledger terms.
     * 
     * @see #inputHash()
     */
    public ByteBuffer cargoHash() {
      return inputHash();
    }
    
    
    /**
     * Returns this block's serial representation on disk.
     */
    public abstract Block toBlock();



    @Override
    public LevelsPointer levelsPointer() {
      return new LevelsPointer(
          blockNo,
          Lists.functorList(
              SkipLedger.skipCount(blockNo),
              level -> prevBlockHash(blockNo, level)));
    }
    
  }
  
  
  
  /** Memo-ised. */
  private class ChainBlock extends SkipBlock {
    
    private final Block block;
    
    ChainBlock(long blockNo, Block block) {
      super(blockNo);
      this.block = block;
    }

    @Override
    public ByteBuffer inputHash() {
      return block.cargoHash();
    }

    // @Override
    // public ByteBuffer prevHash(int level) {
    //   return prevBlockHash(blockNo, level);
    // }

    // @Override
    // public long no() {
    //   return blockNo;
    // }


    /** Memo-ized. */
    @Override
    public ByteBuffer hash() {
      return block.blockHash();
    }
    
    
    public Block toBlock() {
      return block;
    }
  }
  
  
  /** Not memo-ized. */
  private class BuildBlock extends SkipBlock {
    
    private final ByteBuffer cargoHash;
    
    
    BuildBlock(long blockNo, ByteBuffer cargoHash) {
      super(blockNo);
      this.cargoHash = cargoHash;
    }

    @Override
    public Block toBlock() {
      ByteBuffer bhash = hash();
      return new Block(bhash, cargoHash);
    }

    @Override
    public ByteBuffer inputHash() {
      return cargoHash.asReadOnlyBuffer();
    }



    // @Override
    // public ByteBuffer prevHash(int level) {
    //   return prevBlockHash(blockNo, level);
    // }

    // @Override
    // public long no() {
    //   return blockNo;
    // }
    
  }



  
  
  

}
