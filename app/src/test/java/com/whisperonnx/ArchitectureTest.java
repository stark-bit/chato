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

/**
 * The package boundaries in docs/ARCHITECTURE.md, checked on the compiled classes. Checkstyle's
 * ImportControl checks the same rules on imports and runs faster; this also catches fully
 * qualified names and anything else that skips an import.
 */
public class ArchitectureTest {

    private static final String ROOT = "com.whisperonnx";
    private static final List<String> FEATURES =
            Arrays.asList("keyboard", "quickrecord", "voiceinput", "testscreen", "setup", "settings");
    private static final String[] CORE = {ROOT + ".backend..", ROOT + ".recording..", ROOT + ".endpoint.."};
    private static final String SEE = " See docs/ARCHITECTURE.md.";

    private static JavaClasses classes;

    @BeforeClass
    public static void importApp() {
        // Main classes only: unit test classes live in .../debugUnitTest/...
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
                            + "ui.Components, and move shared code into backend, recording, endpoint or ui." + SEE)
                    .check(classes);
        }
    }

    @Test
    public void sharedCodeDoesNotDependOnFeatures() {
        noClasses().that().resideInAnyPackage(ROOT + ".ui..", ROOT + ".backend..", ROOT + ".recording..",
                        ROOT + ".endpoint..", ROOT + ".thirdparty..")
                .should().dependOnClassesThat().resideInAnyPackage(packages(FEATURES))
                .orShould().dependOnClassesThat(registeredNames())
                .because("shared code is used by features, never the other way round." + SEE)
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
                        + "constants; the feature that called them shows the UI and its labels." + SEE)
                .check(classes);
    }

    @Test
    public void thirdPartyIsOnlyUsedByTheBackend() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".backend..", ROOT + ".thirdparty..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".thirdparty..")
                .because("the vendored RTranslator code stays behind backend.Whisper." + SEE)
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".thirdparty..")
                .should().dependOnClassesThat(resideInAPackage(ROOT + "..")
                        .and(not(resideInAPackage(ROOT + ".thirdparty.."))))
                .because("the vendored code is a leaf." + SEE)
                .check(classes);
    }

    @Test
    public void packagesAreFreeOfCycles() {
        slices().matching(ROOT + ".(*)..").should().beFreeOfCycles().check(classes);
    }

    @Test
    public void noCatchAllPackages() {
        noClasses().should().resideInAnyPackage("..utils..", "..util..", "..common..", "..helpers..", "..misc..")
                .because("name a package after what it does, using the terms in docs/GLOSSARY.md." + SEE)
                .check(classes);
    }

    /** The root package holds only R, BuildConfig and the empty classes that keep registered names. */
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
                                    + " only keeps names Android stores, as empty subclasses." + SEE));
                        }
                    }
                })
                .allowEmptyShould(true)
                .check(classes);
    }

    /** Plain logic with unit tests: kept free of Android so the tests run on the JVM. */
    @Test
    public void pureLogicHasNoAndroid() {
        classes().that().haveFullyQualifiedName(ROOT + ".keyboard.CursorDrag")
                .or().haveFullyQualifiedName(ROOT + ".endpoint.SendSession")
                .or().haveFullyQualifiedName(ROOT + ".backend.TdtDecoder")
                .or().haveFullyQualifiedName(ROOT + ".recording.RecordBuffer")
                .or().haveFullyQualifiedName(ROOT + ".recording.WavUtils")
                .should().onlyDependOnClassesThat().resideOutsideOfPackages("android..", "androidx..")
                .because("these are unit-tested on the JVM; pass Android values in from the caller." + SEE)
                .check(classes);
    }

    /** Classes directly in com.whisperonnx, except the generated R and BuildConfig. */
    private static DescribedPredicate<JavaClass> registeredNames() {
        return DescribedPredicate.describe("are in the root package (registered names)", c ->
                c.getPackageName().equals(ROOT)
                        && !c.getName().matches("com\\.whisperonnx\\.(R|BuildConfig)(\\$.*)?"));
    }
}
