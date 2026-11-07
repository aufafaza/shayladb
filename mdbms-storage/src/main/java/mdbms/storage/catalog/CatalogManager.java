package mdbms.storage.catalog;
import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import mdbms.models.schema.*;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;

public final class CatalogManager {
    private static final int MAGIC = 0x534D4334; //SMC4
    private static final int VERSION = 1;
    private final Path catalogFile;
    private final Map<String, TableSchema> schemas = new LinkedHashMap<>();
    private final Map<String, IndexInfo> indexes = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private static int checkedCount(int n) throws IOException{ //helper buat validasi jumlah data yang dibaca dari file catalog
        if(n < 0 || n > 1_000_000) throw new IOException("Invalid catalog entry count");
        return n;
    }
    public CatalogManager(String dataDirectory){
        if(dataDirectory == null || dataDirectory.isBlank()) throw new StorageException(StorageErrorCode.IO_FAILURE, "Catalog data directory must not be blank or null");
        catalogFile = Path.of(dataDirectory).resolve("catalog.bin");
        load();
    }
    public void load(){
        lock.writeLock().lock();
        try {
            if (!Files.exists(catalogFile)) return;
            Map<String, TableSchema> newSchemas = new LinkedHashMap<>();
            Map<String, IndexInfo> newIndexes = new LinkedHashMap<>();
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(catalogFile)))){
                if (in.readInt() != MAGIC || in.readInt() != VERSION) throw new IOException("Unsupported catalog format");
                int n = checkedCount(in.readInt());
                for (int i = 0; i < n; i++){
                    String table = in.readUTF();
                    int c = checkedCount(in.readInt());
                    List<ColumnDefinition> columns = new ArrayList<>();
                    for(int j = 0; j < c; j++){
                        columns.add(new ColumnDefinition(in.readUTF(), DataType.valueOf(in.readUTF()), in.readInt(), in.readBoolean()));
                    }
                    int k = checkedCount(in.readInt());
                    List<String> pks = new ArrayList<>();
                    for(int j = 0; j < k; j++){
                        pks.add(in.readUTF());
                    }
                    int f = checkedCount(in.readInt());
                    List<ForeignKeyConstraint> fks = new ArrayList<>();
                    for(int j = 0; j < f; j++){
                        fks.add(new ForeignKeyConstraint(in.readUTF(),in.readUTF(),in.readUTF(),ForeignKeyAction.valueOf(in.readUTF()),ForeignKeyAction.valueOf(in.readUTF())));
                    }
                    TableSchema schema = new TableSchema(table, List.copyOf(columns), List.copyOf(pks), List.copyOf(fks));
                    validate(schema);
                    if (newSchemas.putIfAbsent(table,schema)!=null) throw new IOException("Duplicate table: "+table);
                }
                int m = checkedCount(in.readInt());
                for(int i=0;i<m;i++) {
                    IndexInfo info = new IndexInfo(in.readUTF(), in.readUTF(), in.readUTF(), IndexType.valueOf(in.readUTF()));
                    TableSchema schema = newSchemas.get(info.tableName());
                    if(schema==null || schema.columns().stream().noneMatch(col->col.name().equals(info.columnName())) || newIndexes.putIfAbsent(info.indexName(),info)!=null)
                        throw new IOException("Invalid index metadata: "+info.indexName());
                }
                if (in.read() != -1) throw new IOException("Unexpected trailing catalog data");
            }
            schemas.clear(); schemas.putAll(newSchemas);
            indexes.clear(); indexes.putAll(newIndexes);
        }catch(IOException | StorageException e) {throw new StorageException(StorageErrorCode.IO_FAILURE, "Cannot load catalog "+catalogFile,e);} //check again
        finally {lock.writeLock().unlock();}
    }
    public void persist(){
        lock.readLock().lock();
        Path temp = null;
        try{
            Files.createDirectories(catalogFile.getParent());
            temp = Files.createTempFile(catalogFile.getParent(), "catalog-", ".tmp");
            try(FileOutputStream stream = new FileOutputStream(temp.toFile()); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(stream))){
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(schemas.size());
                for(TableSchema s: schemas.values()){
                    out.writeUTF(s.tableName());
                    out.writeInt(s.columns().size());
                    for(ColumnDefinition c: s.columns()){
                        out.writeUTF(c.name());
                        out.writeUTF(c.type().name());
                        out.writeInt(c.length());
                        out.writeBoolean(c.nullable());
                    }
                    out.writeInt(s.primaryKey().size());
                    for(String pk: s.primaryKey()){
                        out.writeUTF(pk);
                    }
                    out.writeInt(s.foreignKeys().size());
                    for(ForeignKeyConstraint fk: s.foreignKeys()){
                        out.writeUTF(fk.column());
                        out.writeUTF(fk.referencedTable());
                        out.writeUTF(fk.referencedColumn());
                        out.writeUTF(fk.onDelete().name());
                        out.writeUTF(fk.onUpdate().name());
                    }
                }
                out.writeInt(indexes.size());
                for(IndexInfo i: indexes.values()){
                    out.writeUTF(i.indexName());
                    out.writeUTF(i.tableName());
                    out.writeUTF(i.columnName());
                    out.writeUTF(i.type().name());
                }
                out.flush();
                stream.getFD().sync();
            }
            try{
                Files.move(temp,catalogFile,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            }catch(AtomicMoveNotSupportedException e){
                Files.move(temp,catalogFile,StandardCopyOption.REPLACE_EXISTING);
            }
        }catch(IOException e){
            throw new StorageException(StorageErrorCode.IO_FAILURE, "Cannot persist catalog " + catalogFile, e);
        }
        finally{
            if(temp != null){
                try{Files.deleteIfExists(temp);
                }catch(IOException ignored){}
            }
            lock.readLock().unlock();
        }
    }
    public List<String> listTables(){
        lock.readLock().lock();
        try{
            return List.copyOf(schemas.keySet());
        }finally{
            lock.readLock().unlock();
        }
    }
    public boolean tableExists(String table){
        lock.readLock().lock();
        try{
            return schemas.containsKey(table);
        }finally{
            lock.readLock().unlock();
        }
    }
    public TableSchema getSchema(String table){
        lock.readLock().lock();
        try{
            TableSchema schema = schemas.get(table);
            if(schema == null) throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "Unknown table or table does not exist:" + table);
            return schema;
        }finally{
            lock.readLock().unlock();
        }
    }
    public void saveSchema(TableSchema schema){
        validate(schema);
        lock.writeLock().lock();
        try{
            TableSchema old = schemas.put(schema.tableName(), schema);
            try{
                persist();
            }catch(RuntimeException e){
                if(old == null) schemas.remove(schema.tableName()); else schemas.put(schema.tableName(), old);
                throw e;
            }
        }finally{
            lock.writeLock().unlock();
        }
    }
    public void dropTable(String table){
        lock.writeLock().lock();
        try{
            TableSchema old = schemas.remove(table);
            if(old == null) throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "Unknown table or table does not exist: " + table);
            Map<String, IndexInfo> backup = new LinkedHashMap<>(indexes);
            indexes.values().removeIf(i -> i.tableName().equals(table));
            try{
                persist();
            }catch(RuntimeException e){
                schemas.put(table, old);
                indexes.clear();
                indexes.putAll(backup);
                throw e;
            }
        }finally{
            lock.writeLock().unlock();
        }
    }
    public void registerIndex(IndexInfo info){
        if(info == null) throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Index metadata must not be null");
        lock.writeLock().lock();
        try{
            TableSchema schema = getSchema(info.tableName());
            if(schema.columns().stream().noneMatch(c -> c.name().equals(info.columnName()))){
                throw new StorageException(StorageErrorCode.COLUMN_NOT_FOUND, "Unknown index column or index column does not exist: " + info.columnName());
            }
            if(indexes.containsKey(info.indexName())){
                throw new StorageException(StorageErrorCode.INDEX_ALREADY_EXISTS, "Index already exists: " + info.indexName());
            }
            indexes.put(info.indexName(), info);
            try{
                persist();
            }catch(RuntimeException e){
                indexes.remove(info.indexName());
                throw e;
            }
        }finally{
            lock.writeLock().unlock();
        }
    }
    public void unregisterIndex(String name){
        lock.writeLock().lock();
        try{
            IndexInfo old = indexes.remove(name);
            if(old == null) throw new StorageException(StorageErrorCode.INDEX_NOT_FOUND, "Unknown index or index does not exist: " + name);
            try{
                persist();
            }catch(RuntimeException e){
                indexes.put(name, old);
                throw e;
            }
        }finally{
            lock.writeLock().unlock();
        }
    }
    public List<IndexInfo> getIndexes(String table){
        getSchema(table);
        lock.readLock().lock();
        try{
            return indexes.values().stream().filter(i -> i.tableName().equals(table)).toList();
        }finally{
            lock.readLock().unlock();
        }
    }
    public List<IndexInfo> getAllIndexes(){
        lock.readLock().lock();
        try{
            return List.copyOf(indexes.values());
        }finally{
            lock.readLock().unlock();
        }
    }
    private void validate(TableSchema schema){
        if(schema == null) throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Table schema must not be null");
        if(schema.tableName() == null || schema.tableName().isBlank() || schema.columns() == null || schema.columns().isEmpty() || schema.primaryKey() == null || schema.foreignKeys() == null){
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Table schema is incomplete or invalid");
        }
        Set<String> names = new HashSet<>();
        for(ColumnDefinition c: schema.columns()){
            if( c == null ||c.name() == null || c.name().isBlank() || !names.add(c.name()) || c.type() == null){
                throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Table schema contains a duplicate or invalid column name");
            }
            if((c.type() == DataType.VARCHAR || c.type() == DataType.CHAR) && c.length() <= 0){
                throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Text column needs to have positive length: " + c.name());
            } 
        }
        if(new HashSet<>(schema.primaryKey()).size() != schema.primaryKey().size() || !names.containsAll(schema.primaryKey())){
            throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Primary key references are missing or have duplicate columns");
        }
        for(ForeignKeyConstraint fk: schema.foreignKeys()){
            if(fk == null || !names.contains(fk.column()) || fk.referencedTable() == null || fk.referencedColumn() == null || fk.onDelete() == null || fk.onUpdate() == null){
                throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Foreign key definition is incomplete or invalid");
            }
        }
    }
}