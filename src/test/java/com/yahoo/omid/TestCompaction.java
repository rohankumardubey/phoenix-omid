/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.number.OrderingComparison.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.yahoo.omid.transaction.TTable;
import com.yahoo.omid.transaction.Transaction;
import com.yahoo.omid.transaction.TransactionManager;

public class TestCompaction extends OmidTestBase {
   private static final Logger LOG = LoggerFactory.getLogger(TestCompaction.class);


   @Test public void testDeleteOld() throws Exception {
      try {
         TransactionManager tm = new TransactionManager(hbaseConf);
         TTable tt = new TTable(hbaseConf, TEST_TABLE);

         Transaction t1 = tm.begin();
         LOG.info("Transaction created " + t1);

         byte[] row = Bytes.toBytes("test-simple");
         byte[] fam = Bytes.toBytes(TEST_FAMILY);
         byte[] col = Bytes.toBytes("testdata");
         byte[] col2 = Bytes.toBytes("testdata2");
         byte[] data1 = Bytes.toBytes("testWrite-1");
         byte[] data2 = Bytes.toBytes("testWrite-2verylargedatamuchmoredata than anything ever written to");

         Put p = new Put(row);
         p.add(fam, col, data1);
         tt.put(t1, p);
         tm.commit(t1);

         Transaction t2 = tm.begin();
         p = new Put(row);
         p.add(fam, col, data2);
         tt.put(t2, p);
         tm.commit(t2);

         for (int i = 0; i < 500; ++i) {
            t2 = tm.begin();
            p = new Put(row);
            p.add(fam, col2, data2);
            tt.put(t2, p);
            tm.commit(t2);
         }
         
         HBaseAdmin admin = new HBaseAdmin(hbaseConf);
         admin.flush(TEST_TABLE);
         
         for (int i = 0; i < 500; ++i) {
            t2 = tm.begin();
            p = new Put(row);
            p.add(fam, col2, data2);
            tt.put(t2, p);
            tm.commit(t2);
         }

         Get g = new Get(row);
         g.setMaxVersions();
         g.addColumn(fam, col2);
         Result r = tt.getHTable().get(g);
         int size = r.getColumn(fam, col2).size();
         LOG.info("Size before compaction : " + size);

         admin.compact(TEST_TABLE);
         
         Thread.sleep(2000);
         
         g = new Get(row);
         g.setMaxVersions();
         g.addColumn(fam, col);
         r = tt.getHTable().get(g);
         assertEquals(r.getColumn(fam, col).toString(), 1, r.getColumn(fam, col).size());
         
         g = new Get(row);
         g.setMaxVersions();
         g.addColumn(fam, col2);
         r = tt.getHTable().get(g);
         LOG.info("Size after compaction " + r.getColumn(fam, col2).size());
         assertThat(r.getColumn(fam, col2).size(), is(lessThan(size)));
      } catch (Exception e) {
         LOG.error("Exception occurred", e);
         throw e;
      }
   }

   @Test public void testLimitEqualToColumns() throws Exception {
      try {
         TransactionManager tm = new TransactionManager(hbaseConf);
         TTable tt = new TTable(hbaseConf, TEST_TABLE);

         Transaction t1 = tm.begin();

         byte[] row = Bytes.toBytes("test-simple");
         byte[] row2 = Bytes.toBytes("test-simple2");
         byte[] row3 = Bytes.toBytes("test-simple3");
         byte[] row4 = Bytes.toBytes("test-simple4");
         byte[] fam = Bytes.toBytes(TEST_FAMILY);
         byte[] col = Bytes.toBytes("testdata");
         byte[] col1 = Bytes.add(col, Bytes.toBytes(1));
         byte[] col11 = Bytes.add(col, Bytes.toBytes(11));
         byte[] data = Bytes.toBytes("testWrite-1");
         byte[] data2 = Bytes.toBytes("testWrite-2verylargedatamuchmoredata than anything ever written to");

         Put p = new Put(row);
         for (int i = 0; i < 10; ++i) {
            p.add(fam, Bytes.add(col, Bytes.toBytes(i)), data);
         }
         tt.put(t1, p);
         tm.commit(t1);

         Transaction t2 = tm.begin();
         p = new Put(row2);
         for (int i = 0; i < 10; ++i) {
            p.add(fam, Bytes.add(col, Bytes.toBytes(i)), data);
         }
         tt.put(t2, p);
         tm.commit(t2);

         // fill with data
         for (int i = 0; i < 500; ++i) {
            t2 = tm.begin();
            p = new Put(row4);
            p.add(fam, col11, data2);
            tt.put(t2, p);
            tm.commit(t2);
         }

         HBaseAdmin admin = new HBaseAdmin(hbaseConf);
         admin.flush(TEST_TABLE);

         Transaction t3 = tm.begin();
         p = new Put(row3);
         for (int i = 0; i < 10; ++i) {
            p.add(fam, Bytes.add(col, Bytes.toBytes(i)), data);
         }
         tt.put(t3, p);
         tm.commit(t3);

         // fill with data
         for (int i = 0; i < 500; ++i) {
            t2 = tm.begin();
            p = new Put(row4);
            p.add(fam, col11, data2);
            tt.put(t2, p);
            tm.commit(t2);
         }

         Get g = new Get(row);
         g.setMaxVersions();
         g.addColumn(fam, col1);
         Result r = tt.getHTable().get(g);
         int size = r.getColumn(fam, col1).size();
         LOG.info("Size before compaction : " + size);

         admin.compact(TEST_TABLE);

         Thread.sleep(2000);

         Scan s = new Scan(row);
         s.setMaxVersions();
         s.addColumn(fam, col1);
         ResultScanner rs = tt.getHTable().getScanner(s);
         int count = 0;
         while ((r = rs.next()) != null) {
            count += r.getColumn(fam, col1).size();
         }
         assertEquals(3, count);
      } catch (Exception e) {
         LOG.error("Exception occurred", e);
         throw e;
      }
   }
}