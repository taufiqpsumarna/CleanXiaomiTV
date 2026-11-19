package com.majnioui.tv.channels;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class ChannelListTest {
    @Test
    public void readsHeadersFromTheExtinfLine() {
        List<ChannelList.Channel> list = ChannelList.parse("#EXTM3U\n"
                + "#EXTINF:-1 tvg-id=\"A.id\" tvg-logo=\"l\" http-referrer=\"https://a/\" http-user-agent=\"UA\" group-title=\"G\",A\n"
                + "https://a/live.m3u8\n");
        assertEquals(1, list.size());
        assertEquals("https://a/", list.get(0).referrer);
        assertEquals("UA", list.get(0).userAgent);
        assertEquals("https://a/live.m3u8", list.get(0).url);
    }

    @Test
    public void vlcOptionsWinOverTheExtinfLine() {
        List<ChannelList.Channel> list = ChannelList.parse("#EXTINF:-1 tvg-id=\"A.id\" http-referrer=\"https://old/\" group-title=\"G\",A\n"
                + "#EXTVLCOPT:http-referrer=https://new/\n"
                + "https://a/live.m3u8\n");
        assertEquals("https://new/", list.get(0).referrer);
    }

    @Test
    public void aRefreshedLinkKeepsTheBundledOneAsBackup() {
        List<ChannelList.Channel> bundled = ChannelList.parse("#EXTINF:-1 tvg-id=\"A.id\" group-title=\"G\",A\n"
                + "#EXTVLCOPT:http-referrer=https://bundled/\n"
                + "https://bundled/a.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"B.id\" group-title=\"G\",B\nhttps://same/b.m3u8\n");
        List<ChannelList.Channel> saved = ChannelList.parse("#EXTINF:-1 tvg-id=\"A.id\" group-title=\"G\",A\n"
                + "https://fresh/a.m3u8\n"
                + "#EXTINF:-1 tvg-id=\"B.id\" group-title=\"G\",B\nhttps://same/b.m3u8\n");
        ChannelList.attachBackups(saved, bundled);
        assertTrue(saved.get(0).hasBackup());
        assertEquals("https://bundled/a.m3u8", saved.get(0).backupUrl);
        assertEquals("https://bundled/", saved.get(0).backupReferrer);
        assertFalse(saved.get(1).hasBackup());
        assertNull(saved.get(1).backupUrl);
    }
}
