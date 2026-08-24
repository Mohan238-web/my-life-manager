package com.mylifemanager.app;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.mylifemanager.app.data.AppDatabase;
import com.mylifemanager.app.data.KeyValueEntity;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class RoomPersistenceTest {
    @Test public void roomStoresManagedStateWithoutBrowserStorage() {
        Context context = ApplicationProvider.getApplicationContext();
        AppDatabase database = Room.inMemoryDatabaseBuilder(context, AppDatabase.class).allowMainThreadQueries().build();
        database.dao().put(new KeyValueEntity("powerNotesData", "{\"notes\":[]}", "abc", 10L, true));
        assertEquals("{\"notes\":[]}", database.dao().value("powerNotesData").value);
        database.close();
    }
}
