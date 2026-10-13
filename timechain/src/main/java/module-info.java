module io.crums.tc {
  
  requires transitive io.crums.util.mrkl;
  requires transitive io.crums.jsonimple;
  requires transitive io.crums.sldg.base;
  
  
  requires io.crums.util.io.store;
  requires io.crums.util.tree;
  requires io.crums.stowkwik;
  requires java.net.http;
  
  exports io.crums.tc;
  exports io.crums.tc.client;
  exports io.crums.tc.except;
  exports io.crums.tc.json;
  
}