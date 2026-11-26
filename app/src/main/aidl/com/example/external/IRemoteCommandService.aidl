package com.example.external;

interface IRemoteCommandService {
    int getApiVersion() = 0;
    String listDevices(int apiVersion, long afterDeviceId) = 1;
    String getDevice(int apiVersion, long deviceId) = 2;
    String listApps(int apiVersion, long deviceId, long afterAppId) = 3;
    String execute(int apiVersion, String executionId, long deviceId, String command, String argumentsJson) = 4;
    String getExecution(int apiVersion, String executionId) = 5;
}
