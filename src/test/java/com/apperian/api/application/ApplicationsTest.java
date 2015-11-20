package com.apperian.api.application;

import com.apperian.api.ApiTesting;
import com.apperian.api.ApperianEase;
import com.apperian.api.TestUtil;
import org.junit.Assert;
import org.junit.Test;

public class ApplicationsTest {
    @Test
    public void testListApps() throws Exception {
        if (TestUtil.shouldSkipIntegrationTests()) {
            return;
        }

        ApplicationListResponse response = ApperianEase.APPLICATIONS.list()
                .call(ApiTesting.APERIAN_ENDPOINT);

        TestUtil.assertNoError(response);
        Assert.assertNotNull(response.getApplications());
    }

    @Test
    public void testEnableApp() throws Exception {
        if (TestUtil.shouldSkipIntegrationTests()) {
            return;
        }

        UpdateApplicationMetadataResponse response;
        response = ApperianEase.APPLICATIONS.updateApplicationMetadata(ApiTesting.APP_ID)
                .setEnabled(true)
                .call(ApiTesting.APERIAN_ENDPOINT);

        TestUtil.assertNoError(response);
        Assert.assertTrue(response.updateResult);
    }
}