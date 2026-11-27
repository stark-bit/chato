package com.whisperonnx;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.whisperonnx.ui.Components;

import org.junit.BeforeClass;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

public class ComponentsTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String PACKAGE = "com.whisperonnx";

    private static final String[][] STORED = {
            {"activity-alias", ".MainActivity", "launcher icon"},
            {"activity-alias", ".WhisperRecognizeActivity", "default speech recognizer activity"},
            {"activity-alias", ".QuickRecordActivity", "digital assistant"},
            {"service", ".WhisperInputMethodService", "enabled keyboard"},
            {"service", ".WhisperRecognitionService", "system voice input"},
            {"receiver", ".QuickRecordWidget", "placed widgets"},
    };

    private static final Map<String, String> declared = new HashMap<>();
    private static Document manifest;

    @BeforeClass
    public static void readManifest() throws Exception {
        manifest = parse(new File("src/main/AndroidManifest.xml"));
        for (String tag : new String[]{"activity", "activity-alias", "service", "receiver"}) {
            NodeList nodes = manifest.getElementsByTagName(tag);
            for (int i = 0; i < nodes.getLength(); i++) {
                declared.put(resolve(((Element) nodes.item(i)).getAttributeNS(ANDROID, "name")), tag);
            }
        }
    }

    @Test
    public void storedNamesAreStillDeclared() {
        for (String[] name : STORED) {
            assertEquals(name[1] + " is stored by Android as the " + name[2] + "; renaming it turns that off"
                    + " after an update. Keep it declared as a " + name[0] + ": an <activity-alias> to the"
                    + " moved activity, or an empty subclass in " + PACKAGE, name[0], declared.get(resolve(name[1])));
        }
    }

    @Test
    public void everyDeclaredClassExists() {
        for (Map.Entry<String, String> e : declared.entrySet()) {
            if (!e.getValue().equals("activity-alias")) assertClassExists(e.getKey(), "the manifest");
        }
        NodeList aliases = manifest.getElementsByTagName("activity-alias");
        for (int i = 0; i < aliases.getLength(); i++) {
            String target = resolve(((Element) aliases.item(i)).getAttributeNS(ANDROID, "targetActivity"));
            assertEquals("alias target " + target + " must be a declared activity", "activity", declared.get(target));
        }
    }

    @Test
    public void componentsNamesAreDeclared() throws Exception {
        int checked = 0;
        for (Field f : Components.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            String name = (String) f.get(null);
            assertTrue("Components." + f.getName() + " = " + name + " is not declared in the manifest",
                    declared.containsKey(name));
            checked++;
        }
        assertTrue(checked >= 5);
    }

    @Test
    public void resourceXmlNamesAreDeclaredActivities() throws Exception {
        File[] files = new File("src/main/res/xml").listFiles((d, n) -> n.endsWith(".xml"));
        assertTrue(files != null && files.length > 0);
        for (File file : files) {
            NodeList all = parse(file).getElementsByTagName("*");
            for (int i = 0; i < all.getLength(); i++) {
                Element el = (Element) all.item(i);
                for (String attr : new String[]{"settingsActivity", "configure"}) {
                    String value = el.getAttributeNS(ANDROID, attr);
                    if (!value.isEmpty()) {
                        assertEquals(file.getName() + ": " + attr + "=" + value + " must be a declared activity",
                                "activity", declared.get(resolve(value)));
                    }
                }
            }
        }
    }

    private static void assertClassExists(String name, String where) {
        try {
            Class.forName(name, false, ComponentsTest.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            fail(name + " is named in " + where + " but no such class exists");
        }
    }

    private static String resolve(String name) {
        return name.startsWith(".") ? PACKAGE + name : name;
    }

    private static Document parse(File file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(file);
    }
}
