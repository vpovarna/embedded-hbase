package org.hrw.testkit.hbase;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.ipc.CoprocessorProtocol;
import org.apache.hadoop.hbase.util.Bytes;

import java.io.IOException;
import java.util.*;

/**
 * MockHTable.
 * original MockHTable (by agaoglu) : https://gist.github.com/agaoglu/613217#file_mock_h_table.java
 * Modifications
 * fix filter (by k-mack) : https://gist.github.com/k-mack/4600133
 *     fix batch() : implement all mutate operation and fix result[] count.
 *     fix exists()
 *     fix increment() : wrong return value
 *     check col:umnFamily
 * implement mutateRow()
 * implement getTableName()
 * implement getTableDescriptor()
 * throws RuntimeException when unimplemented method was called.
 * remove some methods for loading data, checking values ...
 */
public class MockHTable implements HTableInterface {
    private final String tableName;
    private final List<String> columnFamilies = new ArrayList<>();

    private NavigableMap<byte[], NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>>> data
            = new TreeMap<>(Bytes.BYTES_COMPARATOR);

    private static List<KeyValue> toKeyValue(byte[] row, NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> rowdata, int maxVersions) {
        return toKeyValue(row, rowdata, 0, Long.MAX_VALUE, maxVersions);
    }

    public MockHTable(String tableName) {
        this.tableName = tableName;
    }

    public MockHTable(String tableName, String... columnFamilies) {
        this.tableName = tableName;
        this.columnFamilies.addAll(Arrays.asList(columnFamilies));
    }

    public void addColumnFamily(String columnFamily) {
        this.columnFamilies.add(columnFamily);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public byte[] getTableName() {
        return tableName.getBytes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Configuration getConfiguration() {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HTableDescriptor getTableDescriptor() throws IOException {
        HTableDescriptor table = new HTableDescriptor(tableName);
        for (String columnFamily : columnFamilies) {
            table.addFamily(new HColumnDescriptor(columnFamily));
        }
        return table;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void mutateRow(RowMutations rm) throws IOException {
        // currently only support Put and Delete
        for (Mutation mutation : rm.getMutations()) {
            if (mutation instanceof Put) {
                put((Put) mutation);
            } else if (mutation instanceof Delete) {
                delete((Delete) mutation);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result append(Append append) throws IOException {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    private static List<KeyValue> toKeyValue(byte[] row, NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> rowdata, long timestampStart, long timestampEnd, int maxVersions) {
        List<KeyValue> ret = new ArrayList<KeyValue>();
        for (byte[] family : rowdata.keySet())
            for (byte[] qualifier : rowdata.get(family).keySet()) {
                int versionsAdded = 0;
                for (Map.Entry<Long, byte[]> tsToVal : rowdata.get(family).get(qualifier).descendingMap().entrySet()) {
                    if (versionsAdded++ == maxVersions)
                        break;
                    Long timestamp = tsToVal.getKey();
                    if (timestamp < timestampStart)
                        continue;
                    if (timestamp > timestampEnd)
                        continue;
                    byte[] value = tsToVal.getValue();
                    ret.add(new KeyValue(row, family, qualifier, timestamp, value));
                }
            }
        return ret;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean exists(Get get) throws IOException {
        Result result = get(get);
        return result != null && result.isEmpty() == false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void batch(List<? extends Row> actions, Object[] results) throws IOException, InterruptedException {
        results = batch(actions);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Object[] batch(List<? extends Row> actions) throws IOException, InterruptedException {
        Object[] results = new Object[actions.size()]; // same size.
        for (int i = 0; i < actions.size(); i++) {
            Row r = actions.get(i);
            if (r instanceof Delete) {
                delete((Delete) r);
                results[i] = new Result();
            }
            if (r instanceof Put) {
                put((Put) r);
                results[i] = new Result();
            }
            if (r instanceof Get) {
                Result result = get((Get) r);
                results[i] = result;
            }
            if (r instanceof Increment) {
                Result result = increment((Increment) r);
                results[i] = result;
            }
            if (r instanceof Append) {
                Result result = append((Append) r);
                results[i] = result;
            }
        }
        return results;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result get(Get get) throws IOException {
        if (!data.containsKey(get.getRow()))
            return new Result();
        byte[] row = get.getRow();
        List<KeyValue> kvs = new ArrayList<KeyValue>();
        if (!get.hasFamilies()) {
            kvs = toKeyValue(row, data.get(row), get.getMaxVersions());
        } else {
            for (byte[] family : get.getFamilyMap().keySet()) {
                if (data.get(row).get(family) == null)
                    continue;
                NavigableSet<byte[]> qualifiers = get.getFamilyMap().get(family);
                if (qualifiers == null || qualifiers.isEmpty())
                    qualifiers = data.get(row).get(family).navigableKeySet();
                for (byte[] qualifier : qualifiers) {
                    if (qualifier == null)
                        qualifier = "".getBytes();
                    if (!data.get(row).containsKey(family) ||
                            !data.get(row).get(family).containsKey(qualifier) ||
                            data.get(row).get(family).get(qualifier).isEmpty())
                        continue;
                    Map.Entry<Long, byte[]> timestampAndValue = data.get(row).get(family).get(qualifier).lastEntry();
                    kvs.add(new KeyValue(row, family, qualifier, timestampAndValue.getKey(), timestampAndValue.getValue()));
                }
            }
        }
        Filter filter = get.getFilter();
        if (filter != null) {
            kvs = filter(filter, kvs);
        }

        return new Result(kvs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result[] get(List<Get> gets) throws IOException {
        List<Result> results = new ArrayList<Result>();
        for (Get g : gets) {
            results.add(get(g));
        }
        return results.toArray(new Result[results.size()]);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Result getRowOrBefore(byte[] row, byte[] family) throws IOException {
        // FIXME: implement
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultScanner getScanner(Scan scan) throws IOException {
        final List<Result> ret = new ArrayList<Result>();
        byte[] st = scan.getStartRow();
        byte[] sp = scan.getStopRow();
        Filter filter = scan.getFilter();

        for (byte[] row : data.keySet()) {
            // if row is equal to startRow emit it. When startRow (inclusive) and
            // stopRow (exclusive) is the same, it should not be excluded which would
            // happen w/o this control.
            if (st != null && st.length > 0 &&
                    Bytes.BYTES_COMPARATOR.compare(st, row) != 0) {
                // if row is before startRow do not emit, pass to next row
                if (st != null && st.length > 0 &&
                        Bytes.BYTES_COMPARATOR.compare(st, row) > 0)
                    continue;
                // if row is equal to stopRow or after it do not emit, stop iteration
                if (sp != null && sp.length > 0 &&
                        Bytes.BYTES_COMPARATOR.compare(sp, row) <= 0)
                    break;
            }

            List<KeyValue> kvs = null;
            if (!scan.hasFamilies()) {
                kvs = toKeyValue(row, data.get(row), scan.getTimeRange().getMin(), scan.getTimeRange().getMax(), scan.getMaxVersions());
            } else {
                kvs = new ArrayList<KeyValue>();
                for (byte[] family : scan.getFamilyMap().keySet()) {
                    if (data.get(row).get(family) == null)
                        continue;
                    NavigableSet<byte[]> qualifiers = scan.getFamilyMap().get(family);
                    if (qualifiers == null || qualifiers.isEmpty())
                        qualifiers = data.get(row).get(family).navigableKeySet();
                    for (byte[] qualifier : qualifiers) {
                        if (data.get(row).get(family).get(qualifier) == null)
                            continue;
                        for (Long timestamp : data.get(row).get(family).get(qualifier).descendingKeySet()) {
                            if (timestamp < scan.getTimeRange().getMin())
                                continue;
                            if (timestamp > scan.getTimeRange().getMax())
                                continue;
                            byte[] value = data.get(row).get(family).get(qualifier).get(timestamp);
                            kvs.add(new KeyValue(row, family, qualifier, timestamp, value));
                            if (kvs.size() == scan.getMaxVersions()) {
                                break;
                            }
                        }
                    }
                }
            }
            if (filter != null) {
                kvs = filter(filter, kvs);
                // Check for early out optimization
                if (filter.filterAllRemaining()) {
                    break;
                }
            }
            if (!kvs.isEmpty()) {
                ret.add(new Result(kvs));
            }
        }

        return new ResultScanner() {
            private final Iterator<Result> iterator = ret.iterator();

            public Iterator<Result> iterator() {
                return iterator;
            }

            public Result[] next(int nbRows) throws IOException {
                ArrayList<Result> resultSets = new ArrayList<Result>(nbRows);
                for (int i = 0; i < nbRows; i++) {
                    Result next = next();
                    if (next != null) {
                        resultSets.add(next);
                    } else {
                        break;
                    }
                }
                return resultSets.toArray(new Result[resultSets.size()]);
            }

            public Result next() throws IOException {
                try {
                    return iterator().next();
                } catch (NoSuchElementException e) {
                    return null;
                }
            }

            public void close() {
            }
        };
    }

    /**
     * Follows the logical flow through the filter methods for a single row.
     *
     * @param filter HBase filter.
     * @param kvs    List of a row's KeyValues
     * @return List of KeyValues that were not filtered.
     */
    private List<KeyValue> filter(Filter filter, List<KeyValue> kvs) {
        filter.reset();

        List<KeyValue> tmp = new ArrayList<KeyValue>(kvs.size());
        tmp.addAll(kvs);

      /*
       * Note. Filter flow for a single row. Adapted from
       * "HBase: The Definitive Guide" (p. 163) by Lars George, 2011.
       * See Figure 4-2 on p. 163.
       */
        boolean filteredOnRowKey = false;
        List<KeyValue> nkvs = new ArrayList<KeyValue>(tmp.size());
        for (KeyValue kv : tmp) {
            if (filter.filterRowKey(kv.getBuffer(), kv.getRowOffset(), kv.getRowLength())) {
                filteredOnRowKey = true;
                break;
            }
            Filter.ReturnCode filterResult = filter.filterKeyValue(kv);
            if (filterResult == Filter.ReturnCode.INCLUDE) {
                nkvs.add(kv);
            } else if (filterResult == Filter.ReturnCode.NEXT_ROW) {
                break;
            } else if (filterResult == Filter.ReturnCode.NEXT_COL || filterResult == Filter.ReturnCode.SKIP) {
                continue;
            }
          /*
           * Ignoring next key hint which is a optimization to reduce file
           * system IO
           */
        }
        if (filter.hasFilterRow() && !filteredOnRowKey) {
            filter.filterRow(nkvs);
        }
        if (filter.filterRow() || filteredOnRowKey) {
            nkvs.clear();
        }
        tmp = nkvs;
        return tmp;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultScanner getScanner(byte[] family) throws IOException {
        Scan scan = new Scan();
        scan.addFamily(family);
        return getScanner(scan);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultScanner getScanner(byte[] family, byte[] qualifier) throws IOException {
        Scan scan = new Scan();
        scan.addColumn(family, qualifier);
        return getScanner(scan);
    }

    private <K, V> V forceFind(NavigableMap<K, V> map, K key, V newObject) {
        V data = map.get(key);
        if (data == null) {
            data = newObject;
            map.put(key, data);
        }
        return data;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(Put put) throws IOException {
        byte[] row = put.getRow();
        NavigableMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>> rowData = forceFind(data, row, new TreeMap<byte[], NavigableMap<byte[], NavigableMap<Long, byte[]>>>(Bytes.BYTES_COMPARATOR));
        for (byte[] family : put.getFamilyMap().keySet()) {
            if (columnFamilies.contains(new String(family)) == false) {
                throw new RuntimeException("Not Exists columnFamily : " + new String(family));
            }
            NavigableMap<byte[], NavigableMap<Long, byte[]>> familyData = forceFind(rowData, family, new TreeMap<byte[], NavigableMap<Long, byte[]>>(Bytes.BYTES_COMPARATOR));
            for (KeyValue kv : put.getFamilyMap().get(family)) {
                kv.updateLatestStamp(Bytes.toBytes(System.currentTimeMillis()));
                byte[] qualifier = kv.getQualifier();
                NavigableMap<Long, byte[]> qualifierData = forceFind(familyData, qualifier, new TreeMap<Long, byte[]>());
                qualifierData.put(kv.getTimestamp(), kv.getValue());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void put(List<Put> puts) throws IOException {
        for (Put put : puts) {
            put(put);
        }

    }

    private boolean check(byte[] row, byte[] family, byte[] qualifier, byte[] value) {
        if (value == null || value.length == 0)
            return !data.containsKey(row) ||
                    !data.get(row).containsKey(family) ||
                    !data.get(row).get(family).containsKey(qualifier);
        else
            return data.containsKey(row) &&
                    data.get(row).containsKey(family) &&
                    data.get(row).get(family).containsKey(qualifier) &&
                    !data.get(row).get(family).get(qualifier).isEmpty() &&
                    Arrays.equals(data.get(row).get(family).get(qualifier).lastEntry().getValue(), value);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkAndPut(byte[] row, byte[] family, byte[] qualifier, byte[] value, Put put) throws IOException {
        if (check(row, family, qualifier, value)) {
            put(put);
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(Delete delete) throws IOException {
        byte[] row = delete.getRow();
        if (data.get(row) == null)
            return;
        if (delete.getFamilyMap().size() == 0) {
            data.remove(row);
            return;
        }
        for (byte[] family : delete.getFamilyMap().keySet()) {
            if (data.get(row).get(family) == null)
                continue;
            if (delete.getFamilyMap().get(family).isEmpty()) {
                data.get(row).remove(family);
                continue;
            }
            for (KeyValue kv : delete.getFamilyMap().get(family)) {
                data.get(row).get(kv.getFamily()).remove(kv.getQualifier());
            }
            if (data.get(row).get(family).isEmpty()) {
                data.get(row).remove(family);
            }
        }
        if (data.get(row).isEmpty()) {
            data.remove(row);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void delete(List<Delete> deletes) throws IOException {
        for (Delete delete : deletes) {
            delete(delete);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean checkAndDelete(byte[] row, byte[] family, byte[] qualifier, byte[] value, Delete delete) throws IOException {
        if (check(row, family, qualifier, value)) {
            delete(delete);
            return true;
        }
        return false;
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public Result increment(Increment increment) throws IOException {
        List<KeyValue> kvs = new ArrayList<KeyValue>();
        Map<byte[], NavigableMap<byte[], Long>> famToVal = increment.getFamilyMap();
        for (Map.Entry<byte[], NavigableMap<byte[], Long>> ef : famToVal.entrySet()) {
            byte[] family = ef.getKey();
            NavigableMap<byte[], Long> qToVal = ef.getValue();
            for (Map.Entry<byte[], Long> eq : qToVal.entrySet()) {
                long newValue = incrementColumnValue(increment.getRow(), family, eq.getKey(), eq.getValue());
                kvs.add(new KeyValue(increment.getRow(), family, eq.getKey(), Bytes.toBytes(newValue)));
            }
        }
        return new Result(kvs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount) throws IOException {
        return incrementColumnValue(row, family, qualifier, amount, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount, boolean writeToWAL) throws IOException {
        if (check(row, family, qualifier, null)) {
            Put put = new Put(row);
            put.add(family, qualifier, Bytes.toBytes(amount));
            put(put);
            return amount;
        }
        long newValue = Bytes.toLong(data.get(row).get(family).get(qualifier).lastEntry().getValue()) + amount;
        data.get(row).get(family).get(qualifier).put(System.currentTimeMillis(),
                Bytes.toBytes(newValue));
        return newValue;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAutoFlush() {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flushCommits() throws IOException {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RowLock lockRow(byte[] row) throws IOException {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void unlockRow(RowLock rl) throws IOException {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends CoprocessorProtocol> T coprocessorProxy(Class<T> protocol, byte[] row) {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends CoprocessorProtocol, R> Map<byte[], R> coprocessorExec(Class<T> protocol, byte[] startKey, byte[] endKey, Batch.Call<T, R> callable) throws IOException, Throwable {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T extends CoprocessorProtocol, R> void coprocessorExec(Class<T> protocol, byte[] startKey, byte[] endKey, Batch.Call<T, R> callable, Batch.Callback<R> callback) throws IOException, Throwable {
        throw new RuntimeException(this.getClass() + " does NOT implement this method.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAutoFlush(boolean autoFlush) {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAutoFlush(boolean autoFlush, boolean clearBufferOnFail) {

    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getWriteBufferSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setWriteBufferSize(long writeBufferSize) throws IOException {

    }
}
