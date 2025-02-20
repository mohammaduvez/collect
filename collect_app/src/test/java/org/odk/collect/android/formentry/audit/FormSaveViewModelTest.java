package org.odk.collect.android.formentry.audit;

import android.net.Uri;

import androidx.lifecycle.LiveData;

import org.javarosa.form.api.FormEntryController;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.odk.collect.android.formentry.FormSaveViewModel;
import org.odk.collect.android.formentry.FormSaver;
import org.odk.collect.android.tasks.SaveFormToDisk;
import org.odk.collect.android.tasks.SaveToDiskResult;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.ALREADY_SAVING;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.CHANGE_REASON_REQUIRED;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.CONSTRAINT_ERROR;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.FINALIZE_ERROR;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.SAVED;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.SAVE_ERROR;
import static org.odk.collect.android.formentry.FormSaveViewModel.SaveResult.State.SAVING;

@RunWith(RobolectricTestRunner.class)
public class FormSaveViewModelTest {

    private static final long CURRENT_TIME = 123L;
    private AuditEventLogger logger;
    private FormSaveViewModel viewModel;
    private FormSaver formSaver;

    @Before
    public void setup() {
        // Useful given some methods will execute AsyncTasks
        Robolectric.getBackgroundThreadScheduler().pause();

        logger = mock(AuditEventLogger.class);
        formSaver = mock(FormSaver.class);

        when(logger.isChangeReasonRequired()).thenReturn(false);

        viewModel = new FormSaveViewModel(() -> CURRENT_TIME, formSaver);
        viewModel.setAuditEventLogger(logger);
    }

    @Test
    public void saveReason_logsChangeReasonAuditEvent() {
        viewModel.setReason("Blah");
        viewModel.saveReason();

        verify(logger).logEvent(AuditEvent.AuditEventType.CHANGE_REASON, null, true, null, CURRENT_TIME, "Blah");
    }

    @Test
    public void saveReason_whenReasonIsValid_returnsTrue() {
        viewModel.setReason("Blah");
        assertThat(viewModel.saveReason(), equalTo(true));
    }

    @Test
    public void saveReason_whenReasonIsNotValid_returnsFalse() {
        viewModel.setReason("");
        assertThat(viewModel.saveReason(), equalTo(false));

        viewModel.setReason("  ");
        assertThat(viewModel.saveReason(), equalTo(false));
    }

    @Test
    public void saveForm_returnsNewSaveResult_inSavingState() {
        LiveData<FormSaveViewModel.SaveResult> saveResult1 = viewModel.saveForm(Uri.parse("file://form"), true, "", false);
        assertThat(saveResult1.getValue().getState(), equalTo(SAVING));

        LiveData<FormSaveViewModel.SaveResult> saveResult2 = viewModel.saveForm(Uri.parse("file://form"), true, "", false);
        assertThat(saveResult2, not(equalTo(saveResult1)));
    }

    @Test
    public void saveForm_wontRunMultipleSavesAtOnce() {
        LiveData<FormSaveViewModel.SaveResult> saveResult1 = viewModel.saveForm(Uri.parse("file://form"), true, "", false);
        LiveData<FormSaveViewModel.SaveResult> saveResult2 = viewModel.saveForm(Uri.parse("file://form"), true, "", false);

        assertThat(saveResult1.getValue().getState(), equalTo(SAVING));
        assertThat(saveResult2.getValue().getState(), equalTo(ALREADY_SAVING));
        assertThat(Robolectric.getBackgroundThreadScheduler().size(), equalTo(1));

        whenFormSaverFinishes(SaveFormToDisk.SAVED);
        assertThat(saveResult1.getValue().getState(), equalTo(SAVED));
        assertThat(saveResult2.getValue().getState(), equalTo(ALREADY_SAVING));
    }

    @Test
    public void saveForm_whenReasonRequiredToSave_returnsSaveResult_inChangeReasonRequiredState() {
        whenReasonRequiredToSave();

        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), true, "", false);
        assertThat(saveResult.getValue().getState(), equalTo(CHANGE_REASON_REQUIRED));
    }

    @Test
    public void whenFormSaverFinishes_saved_setsSaveResultState_toSaved() {
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), true, "", false);

        whenFormSaverFinishes(SaveFormToDisk.SAVED);
        assertThat(saveResult.getValue().getState(), equalTo(SAVED));
    }

    @Test
    public void whenFormSaverFinishes_saved_logsFormSavedAuditEvent() {
        viewModel.saveForm(Uri.parse("file://form"), true, "", false);

        whenFormSaverFinishes(SaveFormToDisk.SAVED);
        verify(logger).logEvent(AuditEvent.AuditEventType.FORM_SAVE, false, CURRENT_TIME);
    }

    @Test
    public void whenFormSaverFinishes_whenViewExiting_logsFormExitAuditEvent() {
        viewModel.saveForm(Uri.parse("file://form"), false, "", true);

        whenFormSaverFinishes(SaveFormToDisk.SAVED);
        verify(logger).logEvent(AuditEvent.AuditEventType.FORM_EXIT, true, CURRENT_TIME);
    }

    @Test
    public void whenFormSaverFinishes_whenFormComplete_andViewExiting_logsFormExitAndFinalizeAuditEvents() {
        viewModel.saveForm(Uri.parse("file://form"), true, "", true);

        whenFormSaverFinishes(SaveFormToDisk.SAVED);
        verify(logger).logEvent(AuditEvent.AuditEventType.FORM_EXIT, false, CURRENT_TIME);
        verify(logger).logEvent(AuditEvent.AuditEventType.FORM_FINALIZE, true, CURRENT_TIME);
    }

    @Test
    public void whenFormSaverFinishes_savedAndExit_setsSaveResultState_toSaved() {
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(SaveFormToDisk.SAVED_AND_EXIT);
        assertThat(saveResult.getValue().getState(), equalTo(SAVED));
    }

    @Test
    public void whenFormSaverFinishes_saveError_setSaveResultState_toSaveErrorWithMessage() {
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(SaveFormToDisk.SAVE_ERROR, "OH NO");
        assertThat(saveResult.getValue().getState(), equalTo(SAVE_ERROR));
        assertThat(saveResult.getValue().getMessage(), equalTo("OH NO"));
    }

    @Test
    public void whenFormSaverFinishes_saveError_logsSaveErrorAuditEvent() {
        viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(SaveFormToDisk.SAVE_ERROR);
        verify(logger).logEvent(AuditEvent.AuditEventType.SAVE_ERROR, true, CURRENT_TIME);
    }

    @Test
    public void whenFormSaverFinishes_encryptionError_setSaveResultState_toFinalizeErrorWithMessage() {
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(SaveFormToDisk.ENCRYPTION_ERROR, "OH NO");
        assertThat(saveResult.getValue().getState(), equalTo(FINALIZE_ERROR));
        assertThat(saveResult.getValue().getMessage(), equalTo("OH NO"));
    }

    @Test
    public void whenFormSaverFinishes_encryptionError_logsFinalizeErrorAuditEvent() {
        viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(SaveFormToDisk.ENCRYPTION_ERROR);
        verify(logger).logEvent(AuditEvent.AuditEventType.FINALIZE_ERROR, true, CURRENT_TIME);
    }

    @Test
    public void whenFormSaverFinishes_answerConstraintViolated_setSaveResultState_toConstraintError() {
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(FormEntryController.ANSWER_CONSTRAINT_VIOLATED);
        assertThat(saveResult.getValue().getState(), equalTo(CONSTRAINT_ERROR));
    }

    @Test
    public void whenFormSaverFinishes_answerConstraintViolated_finalizesAndLogsConstraintErrorAuditEvent() {
        viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(FormEntryController.ANSWER_CONSTRAINT_VIOLATED);

        InOrder verifier = inOrder(logger);
        verifier.verify(logger).exitView();
        verifier.verify(logger).logEvent(AuditEvent.AuditEventType.CONSTRAINT_ERROR, true, CURRENT_TIME);
    }

    @Test
    public void whenFormSaverFinishes_answerRequiredButEmpty_setSaveResultState_toConstraintError() {
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        whenFormSaverFinishes(FormEntryController.ANSWER_REQUIRED_BUT_EMPTY);
        assertThat(saveResult.getValue().getState(), equalTo(CONSTRAINT_ERROR));
    }

    @Test
    public void whenFormSaverFinishes_isSaving_returnsFalse() {
        assertThat(viewModel.isSaving(), equalTo(false));

        viewModel.saveForm(Uri.parse("file://form"), false, "", false);
        assertThat(viewModel.isSaving(), equalTo(true));

        whenFormSaverFinishes(SaveFormToDisk.SAVED);
        assertThat(viewModel.isSaving(), equalTo(false));
    }

    @Test
    public void whenReasonRequiredToSave_saveReason_setsSaveResultState_toSaving() {
        whenReasonRequiredToSave();
        LiveData<FormSaveViewModel.SaveResult> saveResult = viewModel.saveForm(Uri.parse("file://form"), false, "", false);

        viewModel.setReason("blah");
        viewModel.saveReason();
        assertThat(saveResult.getValue().getState(), equalTo(SAVING));
    }

    private void whenReasonRequiredToSave() {
        when(logger.isChangeReasonRequired()).thenReturn(true);
        when(logger.isChangesMade()).thenReturn(true);
        when(logger.isEditing()).thenReturn(true);
    }

    private void whenFormSaverFinishes(int result) {
        whenFormSaverFinishes(result, null);
    }

    private void whenFormSaverFinishes(int result, String message) {
        SaveToDiskResult saveToDiskResult = new SaveToDiskResult();
        saveToDiskResult.setSaveResult(result, true);
        saveToDiskResult.setSaveErrorMessage(message);

        when(formSaver.save(any(), anyBoolean(), any(), anyBoolean(), any())).thenReturn(saveToDiskResult);
        Robolectric.getBackgroundThreadScheduler().runOneTask();
    }
}