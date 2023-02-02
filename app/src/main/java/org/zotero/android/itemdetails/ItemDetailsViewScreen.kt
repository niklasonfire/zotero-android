package org.zotero.android.itemdetails

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.zotero.android.architecture.ui.CustomLayoutSize
import org.zotero.android.dashboard.data.ItemDetailField
import org.zotero.android.uicomponents.Strings
import org.zotero.android.uicomponents.misc.CustomDivider
import org.zotero.android.uicomponents.theme.CustomPalette
import org.zotero.android.uicomponents.theme.CustomTheme


@Composable
internal fun ItemDetailsViewScreen(
    viewState: ItemDetailsViewState,
    viewModel: ItemDetailsViewModel,
    layoutType: CustomLayoutSize.LayoutType
) {

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp)
    ) {
        item {
            Title(viewState, layoutType)
            CustomDivider()
        }

        item {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                ItemType(viewState, layoutType)
                ListOfCreatorRows(viewState, layoutType)
                ListOfFieldRows(viewState, layoutType)
                DatesRows(
                    dateAdded = viewState.data.dateAdded,
                    dateModified = viewState.data.dateModified,
                    layoutType = layoutType,
                    showDivider = false
                )

                if (!viewState.data.isAttachment) {
                    CustomDivider(
                        modifier = Modifier
                            .padding(top = 4.dp)
                    )
                    AbstractFieldRow(
                        detailValue = viewState.data.abstract ?: "",
                        layoutType = layoutType
                    )
                }

            }

        }
        notesTagsAndAttachmentsBlock(
            viewState = viewState,
            layoutType = layoutType,
            onNoteClicked = { viewModel.openNoteEditor(it) },
            onAddNote = { viewModel.onAddNote() },
            onAttachmentClicked = { viewModel.openAttachment(it) },
            onAddAttachment = { viewModel.onAddAttachment() },
            onTagClicked = { viewModel.openTag(it) },
            onAddTag = { viewModel.onAddTag() },
            onNoteLongClicked = viewModel::onNoteLongClick,
            onAttachmentLongClicked = {},
            onTagLongClicked = viewModel::onTagLongClick,
        )
    }
}

@Composable
private fun ItemType(viewState: ItemDetailsViewState, layoutType: CustomLayoutSize.LayoutType) {
    FieldRow(
        detailTitle = stringResource(id = Strings.item_type),
        detailValue = viewState.data.localizedType,
        layoutType = layoutType,
        showDivider = false
    )
}

@Composable
private fun ListOfCreatorRows(
    viewState: ItemDetailsViewState,
    layoutType: CustomLayoutSize.LayoutType
) {
    for (creatorId in viewState.data.creatorIds) {
        val creator = viewState.data.creators.get(creatorId) ?: continue
        val title = creator.localizedType
        val value = creator.name
        FieldRow(
            detailTitle = title,
            detailValue = value,
            layoutType = layoutType,
            showDivider = false
        )
    }

}

@Composable
fun ListOfFieldRows(
    viewState: ItemDetailsViewState,
    layoutType: CustomLayoutSize.LayoutType
) {
    for (fieldId in viewState.data.fieldIds) {
        val field = viewState.data.fields.get(fieldId) ?: continue
        val title = field.name
        var value = field.additionalInfo?.get(ItemDetailField.AdditionalInfoKey.formattedDate)
            ?: field.value
        value = if (value.isEmpty()) " " else value
        val textColor = if (field.isTappable) {
            CustomPalette.Blue
        } else {
            CustomTheme.colors.primaryContent
        }
        FieldRow(
            detailTitle = title,
            detailValue = value,
            layoutType = layoutType,
            textColor = textColor,
            showDivider = false
        )
    }
}

@Composable
private fun Title(
    viewState: ItemDetailsViewState,
    layoutType: CustomLayoutSize.LayoutType
) {
    Text(
        modifier = Modifier
            .padding(bottom = 12.dp, end = 12.dp, start = 12.dp),
        text = viewState.data.title,
        color = CustomTheme.colors.primaryContent,
        style = CustomTheme.typography.default,
        fontSize = layoutType.calculateTitleTextSize(),
    )
}

@Composable
internal fun AbstractFieldRow(
    detailValue: String,
    layoutType: CustomLayoutSize.LayoutType,
    textColor: Color = CustomTheme.colors.primaryContent,
) {
    Column {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            modifier = Modifier.align(Alignment.Start),
            text = stringResource(id = Strings.abstractS),
            color = CustomTheme.colors.secondaryContent,
            style = CustomTheme.typography.default,
            fontSize = layoutType.calculateTextSize(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            modifier = Modifier,
            text = detailValue,
            color = textColor,
            style = CustomTheme.typography.default,
            fontSize = layoutType.calculateTextSize(),
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

}