package com.slugyzeon.plugin.amazonmusic;

import java.util.Map;

public class TestMain {
    public static void main(String[] args) throws Exception {
        AmazonMusicApiHandler api = new AmazonMusicApiHandler("IN");
        Map<String, Object> track = api.fetchEntity("https://music.amazon.in/tracks/B074W3MJ26", "B074W3MJ26", "track");
        System.out.println(track);
    }
}
