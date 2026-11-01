package ai.openpush.android.inappmessages;

import java.util.Objects;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class JavaInAppSmokeTest {
    @Test
    public void theSurfaceIsCallableFromJavaWithoutINSTANCE() {
        OpenPushInAppMessages.addTrigger("k", "v");
        OpenPushInAppMessages.setPaused(true);
        OpenPushInAppMessages.setPaused(false);

        InAppMessageLifecycleEvent lifecycle = new InAppMessageLifecycleEvent("iam-1", "futurePhase");
        InAppMessageLifecycleEvent lifecycleCopy = lifecycle.copy("iam-2", "didDisplay");
        InAppMessageClickEvent click = new InAppMessageClickEvent(
                "iam-1", "buy", null, null, true);
        InAppMessageClickEvent clickCopy = click.copy(
                "iam-2", "save", null, null, false);
        org.junit.Assert.assertEquals(InAppLifecycleStage.UNKNOWN, lifecycle.getStage());
        org.junit.Assert.assertEquals(InAppLifecycleStage.DID_DISPLAY, lifecycleCopy.getStage());
        org.junit.Assert.assertTrue(Objects.equals("save", clickCopy.getActionId()));
    }
}
