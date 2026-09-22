package com.example.channel.importer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Разбор .cdbx — XML-выгрузки базы каналов старой системы. DOM: файл — единицы мегабайт.
 * Перед {@code <?xml} в настоящих файлах бывает мусор (BOM и буква «ж»), поэтому документ
 * берётся с первого {@code <?xml}.
 */
public final class CdbxParser {

    private static final String SEPARATOR = " --";
    private static final List<String> DRIVER_FIELDS = List.of("enabled", "access", "descr", "maxsubtypescount");

    private CdbxParser() {
    }

    public static CdbxFile parse(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        int start = text.indexOf("<?xml");
        if (start < 0) {
            throw new IllegalArgumentException("Это не .cdbx: нет заголовка <?xml");
        }
        Document document = read(text.substring(start).getBytes(StandardCharsets.UTF_8));

        Element driver = firstElement(document.getDocumentElement(), "driver");
        if (driver == null) {
            throw new IllegalArgumentException("Это не .cdbx: нет описания драйвера");
        }
        Map<String, String> driverFields = new LinkedHashMap<>();
        for (String field : DRIVER_FIELDS) {
            String value = childText(driver, field);
            if (value != null) {
                driverFields.put(field, value);
            }
        }
        Element communication = firstElement(driver, "communication");
        if (communication != null) {
            NodeList parameters = communication.getElementsByTagNameNS("*", "parameter");
            for (int i = 0; i < parameters.getLength(); i++) {
                Element parameter = (Element) parameters.item(i);
                String name = childText(parameter, "name");
                if (name != null) {
                    driverFields.put(name, nullToEmpty(childText(parameter, "value")));
                }
            }
        }

        List<CdbxChannel> channels = new ArrayList<>();
        NodeList subtypes = driver.getElementsByTagNameNS("*", "subtype");
        for (int i = 0; i < subtypes.getLength(); i++) {
            Element subtype = (Element) subtypes.item(i);
            String group = nullToEmpty(childText(subtype, "sdrvname"));
            NodeList subtypeChannels = subtype.getElementsByTagNameNS("*", "channel");
            for (int j = 0; j < subtypeChannels.getLength(); j++) {
                channels.add(channel(group, (Element) subtypeChannels.item(j)));
            }
        }
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("В файле нет каналов");
        }
        return new CdbxFile(driverFields, channels);
    }

    private static CdbxChannel channel(String group, Element channel) {
        String descr = nullToEmpty(childText(channel, "descr"));
        int separator = descr.indexOf(SEPARATOR);
        String name = separator < 0 ? descr.trim() : descr.substring(0, separator).trim();
        String description = separator < 0 ? "" : descr.substring(separator + SEPARATOR.length()).trim();
        return new CdbxChannel(group, name, description,
                nullToEmpty(childText(channel, "enabled")), nullToEmpty(childText(channel, "requesttype")),
                nullToEmpty(childText(channel, "requestperiod")), nullToEmpty(childText(channel, "delta")),
                nullToEmpty(childText(channel, "apptime")), nullToEmpty(childText(channel, "protocol")));
    }

    private static Document read(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            // Файл приходит от пользователя: внешние сущности и DOCTYPE запрещены (XXE).
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IllegalArgumentException("Файл .cdbx не разобран: " + e.getMessage(), e);
        }
    }

    /** Первый прямой потомок с таким локальным именем. */
    private static Element firstElement(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                return element;
            }
        }
        return null;
    }

    private static String childText(Element parent, String localName) {
        Element child = firstElement(parent, localName);
        return child == null ? null : child.getTextContent().trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
