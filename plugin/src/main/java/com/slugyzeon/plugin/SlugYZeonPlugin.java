package com.slugyzeon.plugin;

import com.slugyzeon.plugin.config.SlugYZeonConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SlugYZeonPlugin {

    private static final Logger log = LoggerFactory.getLogger(SlugYZeonPlugin.class);

    public SlugYZeonPlugin(SlugYZeonConfig config) {
        log.info("Loading SlugYZeoN plugin...");
    }
}
