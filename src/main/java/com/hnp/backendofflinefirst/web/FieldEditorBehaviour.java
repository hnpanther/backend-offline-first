package com.hnp.backendofflinefirst.web;

import com.hnp.backendofflinefirst.domain.FieldValidationSupport;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport.EntryLimit;
import com.hnp.backendofflinefirst.domain.FieldValidationSupport.FieldBehaviour;
import com.hnp.backendofflinefirst.service.LookupListService;

/**
 * The field-editor post, turned into the behaviour {@code build} stores.
 *
 * <p>The ad-hoc form editor exposes parent filtering and date windows. Other editors retain
 * their existing FieldBehaviour.fromForm contract until they explicitly support these controls.
 */
final class FieldEditorBehaviour {

    private FieldEditorBehaviour() {}

    static FieldBehaviour of(LookupListService lists, String dataType,
                             String allowCustomMarker, Boolean allowCustomChecked,
                             String signatureScope,
                             Double entryMin, Double entryMax,
                             String integerOnlyMarker, Boolean integerOnlyChecked,
                             String lookupList,
                             String acceptsCodeMarker, Boolean acceptsCodeChecked,
                             String filterMarker, Boolean filterChecked,
                             String dateTimeMin, String dateTimeMax,
                             Integer minutesBefore, Integer minutesAfter) {
        String list = lists.listCodeForField(dataType, lookupList);
        return FieldBehaviour.fromEditor(dataType, allowCustomMarker, allowCustomChecked, signatureScope,
                EntryLimit.fromForm(entryMin, entryMax, integerOnlyMarker, integerOnlyChecked),
                list, acceptsCodeMarker, acceptsCodeChecked,
                filterMarker, filterChecked, lists.parentListCode(list),
                dateTimeMin, dateTimeMax, minutesBefore, minutesAfter);
    }
}
