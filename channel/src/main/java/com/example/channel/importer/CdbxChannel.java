package com.example.channel.importer;

/** Канал .cdbx: имя и описание уже разделены по « --». */
public record CdbxChannel(String group, String name, String description, String enabled, String requestType,
                          String requestPeriod, String delta, String appTime, String protocol) {
}
