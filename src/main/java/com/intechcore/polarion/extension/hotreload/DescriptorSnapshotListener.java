package com.intechcore.polarion.extension.hotreload;

import com.intechcore.polarion.extension.hotreload.service.RedeployService;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

/**
 * Takes the descriptor snapshot at server start, while every installed extension jar still exists.
 */
public class DescriptorSnapshotListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        new RedeployService().snapshotInstalledExtensions();
    }
}
