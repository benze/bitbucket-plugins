package org.wada.bb;

import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.io.FileDescriptor;
import java.io.SyncFailedException;
import java.nio.channels.FileChannel;
import java.sql.SQLException;

import static org.junit.Assert.*;

public class PersistentCacheTest {

    @Test
    public void put() throws SQLException, InterruptedException, SyncFailedException {
        PersistentCache cache = new PersistentCache();
        cache.open("putTest", 1);
        for( int i = 0; i < 1000; i++){
            cache.put(i, "JUNIT-" + i);
        }
        cache.close();


        cache.open("putTest", 1);
        for( int i = 0; i < 1000; i++){
            assertNotNull(cache.get(i));
        }
        cache.close();

    }
}