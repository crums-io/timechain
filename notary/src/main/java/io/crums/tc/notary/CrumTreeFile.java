/*
 * Copyright 2024 Babak Farhang
 */
package io.crums.tc.notary;


import static io.crums.tc.Constants.HASH_WIDTH;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.nio.channels.FileChannel;
import java.util.List;
import java.util.Objects;

import io.crums.util.api.Opening;
import io.crums.util.io.channels.ChannelUtils;
import io.crums.util.io.store.table.SortedTable;
import io.crums.util.io.store.table.order.RowOrder;
import io.crums.tc.Crum;
import io.crums.tc.notary.except.NotaryException;
import io.crums.util.collections.Lists;

/**
 * 
 */
public class CrumTreeFile extends CrumMerkleTree implements Channel {

  /**
   * Offset in file where the leaf (crum) count is found.
   * There's no header in the file, so this is zero (0).
   */
  public final static int LEAF_COUNT_OFFSET = 0;

  private final static int NODE_DATA_HEAD =
      CrumTreeBuffer.NODE_DATA_HEAD + LEAF_COUNT_OFFSET;
  

  /**
   * <p>
   * Row ordering for {@linkplain SortedTable}. Defined in a way
   * that is also suitable for prefix search (altho not using this
   * capability yet). This just considers hashes, ignoring the UTC
   * --the hashes in the table are distinct.
   * </p><p>
   * Note: this hash order is not lexical in the obvious way.
   * The arguments are compared as <em>signed</em> bytes. The choice
   * doesn't really matter here, but we've kept it consistent with how
   * {@code ByteBuffer}s compare.
   * </p>
   */
  final static RowOrder CRUM_ORDER = new RowOrder() {
    @Override
    public int compareRows(ByteBuffer rowA, ByteBuffer rowB) {
      return rowA == rowB ? 0 : rowA.compareTo(rowB);
    }
  };
  
  
  private static int countLeaves(FileChannel file) throws IOException {
    ByteBuffer bint = ByteBuffer.wrap(new byte[4]);
    ChannelUtils.readRemaining(file, LEAF_COUNT_OFFSET, bint);
    return bint.flip().getInt();
  }
  
  
  

  private File file;
  private final FileChannel ch;
  

  /**
   * Creates a new instance with the given filepath.
   */
  public CrumTreeFile(File file) throws IOException, NotaryException {
    this(file, NotaryLog.NULL);
  }
  
  
  public CrumTreeFile(File file, NotaryLog log)
      throws IOException, NotaryException {
    
    this(Opening.READ_ONLY.openChannel(file), file, log);
  }
  
  
  
  private CrumTreeFile(FileChannel ch, File file, NotaryLog log)
      throws IOException, NotaryException {
    super(countLeaves(ch), log);
    this.ch = ch;
    this.file = file;
    {
      long tally = crumsTableHeadOffset();
      tally += idx().count() * Crum.DATA_SIZE;
      
      if (tally != ch.size()) {
        ch.close();
        throw new NotaryException(
            "file underflow: " + file + "\n" +
            "expected " + tally + " bytes; actual is " + ch.size());
      }
    }
    
  }
  
  private int crumsTableHeadOffset() {
    return NODE_DATA_HEAD + idx().totalCount() *  HASH_WIDTH;
  }
  
  public final File file() {
    return file;
  }

  @Override
  public boolean isOpen() {
    return ch.isOpen();
  }

  @Override
  public void close() {
    try {
      ch.close();
    } catch (IOException iox) {
      throw new NotaryException(iox);
    }
  }

  @Override
  public List<Crum> crums() {
    return new Lists.RandomAccessList<Crum>() {

      @Override
      public Crum get(int index) {
        Objects.checkIndex(index, size());
        long crumsZeroOffset =
            NODE_DATA_HEAD +
            idx().totalCount() * (long) HASH_WIDTH;
        
        long offset = crumsZeroOffset + index * (long) Crum.DATA_SIZE;
        
        ByteBuffer crum = ByteBuffer.allocate(Crum.DATA_SIZE);
        try {
          ChannelUtils.readRemaining(ch, offset, crum);
        } catch (IOException iox) {
          throw new NotaryException("at index " + index, iox);
        }
        return new Crum(crum.flip());
      }

      @Override
      public int size() {
        return idx().count();
      }
    };
  }
  
  
  

  @Override
  public byte[] data(int level, int index) {
    try {
      byte[] nodeData = new byte[HASH_WIDTH];
      long offset =
          NODE_DATA_HEAD +
          idx().serialIndex(level, index) * (long) HASH_WIDTH;
      
      ChannelUtils.readRemaining(ch, offset, ByteBuffer.wrap(nodeData));
      return nodeData;
    
    } catch (IOException iox) {
      throw new NotaryException(iox);
    }
  }
  

  /**
   * Returns the crums as a fixed-width, sorted table.
   */
  @SuppressWarnings("resource")
  SortedTable crumsTable() {
    try {
      // cloned, so caller can't close this instance's underlying channel
      return new SortedTable(
          ch, crumsTableHeadOffset(), Crum.DATA_SIZE, CRUM_ORDER).clone();
      
    } catch (IOException iox) {
      throw new NotaryException(iox);
    }
  }

}
