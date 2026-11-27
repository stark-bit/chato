package com.whisperonnx;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.Assert.assertTrue;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ArchitectureTest {
    private static final String ROOT = "com.whisperonnx";
    private static final List<String> FEATURES =
            Arrays.asList("keyboard", "quickrecord", "voiceinput", "testscreen", "setup", "settings");
    private static final String[] CORE = {ROOT + ".backend..", ROOT + ".recording..", ROOT + ".endpoint..", ROOT + ".prefs.."};
    private static final List<String> SHARED = Arrays.asList("ui", "backend", "recording", "endpoint", "prefs", "thirdparty");

    private static JavaClasses classes;

    @BeforeClass
    public static void importApp() {
        // External: Gradle compiles unit test classes into .../debugUnitTest/...
        classes = new ClassFileImporter()
                .withImportOption(location -> !location.contains("UnitTest"))
                .importPackages(ROOT);
        assertTrue("found only " + classes.size() + " classes; is the import path filter wrong?",
                classes.size() > 50);
    }

    private static String[] packages(List<String> names) {
        return names.stream().map(n -> ROOT + "." + n + "..").toArray(String[]::new);
    }

    @Test
    public void featuresDoNotDependOnEachOther() {
        for (String feature : FEATURES) {
            List<String> others = new ArrayList<>(FEATURES);
            others.remove(feature);
            noClasses().that().resideInAPackage(ROOT + "." + feature + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(packages(others))
                    .because("features stay independent: open another feature's screen through "
                            + "ui.Components, and move shared code into backend, recording, endpoint or ui.")
                    .check(classes);
        }
    }

    @Test
    public void sharedCodeDoesNotDependOnFeatures() {
        noClasses().that().resideInAnyPackage(ROOT + ".ui..", ROOT + ".backend..", ROOT + ".recording..",
                        ROOT + ".endpoint..", ROOT + ".prefs..", ROOT + ".thirdparty..")
                .should().dependOnClassesThat().resideInAnyPackage(packages(FEATURES))
                .orShould().dependOnClassesThat(registeredNames())
                .because("shared code is used by features, never the other way round.")
                .check(classes);
    }

    @Test
    public void coreHasNoUi() {
        noClasses().that().resideInAnyPackage(CORE)
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".ui..",
                        "android.widget..", "android.view..", "android.app..",
                        "androidx.appcompat..", "com.google.android.material..")
                .orShould().dependOnClassesThat().haveNameMatching("com\\.whisperonnx\\.R(\\$.*)?")
                .because("backend, recording and endpoint report state through listeners and "
                        + "constants; the feature that called them shows the UI and its labels.")
                .check(classes);
    }

    @Test
    public void thirdPartyIsOnlyUsedByTheBackend() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".backend..", ROOT + ".thirdparty..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".thirdparty..")
                .because("the vendored RTranslator code stays behind backend.Whisper.")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".thirdparty..")
                .should().dependOnClassesThat(resideInAPackage(ROOT + "..")
                        .and(not(resideInAPackage(ROOT + ".thirdparty.."))))
                .because("the vendored code is a leaf.")
                .check(classes);
    }

    @Test
    public void everyPackageIsAFeatureOrShared() {
        classes().that().resideOutsideOfPackages(ROOT, ROOT + ".databinding")
                .should(new ArchCondition<JavaClass>("be in a feature or shared package") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        String top = c.getPackageName().substring(ROOT.length() + 1).split("\\.")[0];
                        if (!FEATURES.contains(top) && !SHARED.contains(top)) {
                            events.add(SimpleConditionEvent.violated(c, c.getName() + " is in package " + top
                                    + ", which is neither a feature nor shared. A new feature gets its own"
                                    + " package: add it to FEATURES here and to config/checkstyle/import-control.xml."));
                        }
                    }
                })
                .check(classes);
    }

    @Test
    public void onlyPrefsTouchesSharedPreferences() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".prefs..", ROOT + ".thirdparty..")
                .should().dependOnClassesThat().haveFullyQualifiedName("android.content.SharedPreferences")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("android.content.SharedPreferences$Editor")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("androidx.preference.PreferenceManager")
                .orShould().callMethod("android.content.Context", "getSharedPreferences", "java.lang.String", "int")
                .because("each setting has one key and one default, in prefs.Prefs. Add a typed getter and"
                        + " setter there instead of reading SharedPreferences with a string key.")
                .check(classes);
    }

    @Test
    public void prefsIsALeaf() {
        noClasses().that().resideInAPackage(ROOT + ".prefs..")
                .should().dependOnClassesThat(resideInAPackage(ROOT + "..")
                        .and(not(resideInAPackage(ROOT + ".prefs.."))))
                .because("every package reads settings, so prefs depends on none of them.")
                .check(classes);
    }

    @Test
    public void packagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    public void noCatchAllPackages() {
        noClasses().should().resideInAnyPackage("..utils..", "..util..", "..common..", "..helpers..", "..misc..")
                .because("name a package after what it does, using the terms in docs/GLOSSARY.md.")
                .check(classes);
    }

    @Test
    public void rootPackageHoldsOnlyRegisteredNames() {
        classes().that(registeredNames())
                .should(new ArchCondition<JavaClass>("be an empty subclass of a class in a feature package") {
                    @Override
                    public void check(JavaClass c, ConditionEvents events) {
                        boolean empty = c.getMethods().isEmpty() && c.getFields().isEmpty();
                        String parent = c.getRawSuperclass().map(JavaClass::getPackageName).orElse("");
                        boolean extendsFeature = FEATURES.stream().anyMatch(f -> parent.equals(ROOT + "." + f));
                        if (!empty || !extendsFeature) {
                            events.add(SimpleConditionEvent.violated(c, c.getName()
                                    + " is in the root package. Put code in a feature package; the root package"
                                    + " only keeps names Android stores, as empty subclasses."));
                        }
                    }
                })
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    public void pureLogicHasNoAndroid() {
        classes().that().haveFullyQualifiedName(ROOT + ".keyboard.CursorDrag")
                .or().haveFullyQualifiedName(ROOT + ".endpoint.SendSession")
                .or().haveFullyQualifiedName(ROOT + ".backend.TdtDecoder")
                .or().haveFullyQualifiedName(ROOT + ".recording.RecordBuffer")
                .or().haveFullyQualifiedName(ROOT + ".recording.WavUtils")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages("android..", "androidx..", "ai.onnxruntime..")
                .because("these are unit-tested on the JVM; pass Android and ONNX values in from the caller.")
                .check(classes);
    }

    private static DescribedPredicate<JavaClass> registeredNames() {
        return DescribedPredicate.describe("are in the root package (registered names)", c ->
                c.getPackageName().equals(ROOT)
                        && !c.getName().matches("com\\.whisperonnx\\.(R|BuildConfig)(\\$.*)?"));
    }
}
