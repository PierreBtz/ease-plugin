package com.apperian.api.application;

import com.apperian.api.ApperianResourceID;
import com.fasterxml.jackson.annotation.JsonProperty;

public class Application {
    @JsonProperty("psk")
    ApperianResourceID id;

    @Override
    public String toString() {
        return "Application{" +
                "id=" + id +
                '}';
    }
}
// TODO add other attributes from here https://help.apperian.com/pages/viewpage.action?pageId=3441084