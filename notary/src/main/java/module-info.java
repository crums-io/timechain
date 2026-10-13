module io.crums.tc.notary {
  
  requires transitive io.crums.tc;
  
  // look into removing io.store (no longer needed)
  requires io.crums.util.io.store;
  requires io.crums.util.tree;
  requires io.crums.stowkwik;
  
  exports io.crums.tc.notary;
  exports io.crums.tc.notary.d;
  exports io.crums.tc.notary.except;
  
}