package org.zotero.android.screens.allitems

import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.zotero.android.architecture.BaseViewModel2
import org.zotero.android.architecture.EventBusConstants
import org.zotero.android.architecture.LCE2
import org.zotero.android.architecture.ScreenArguments
import org.zotero.android.architecture.ViewEffect
import org.zotero.android.architecture.ViewState
import org.zotero.android.architecture.coroutines.Dispatchers
import org.zotero.android.architecture.ifFailure
import org.zotero.android.architecture.navigation.NavigationParamsMarshaller
import org.zotero.android.database.DbError
import org.zotero.android.database.DbWrapper
import org.zotero.android.database.objects.Attachment
import org.zotero.android.database.objects.ItemTypes
import org.zotero.android.database.objects.RItem
import org.zotero.android.database.requests.AssignItemsToCollectionsDbRequest
import org.zotero.android.database.requests.CreateAttachmentsDbRequest
import org.zotero.android.database.requests.CreateNoteDbRequest
import org.zotero.android.database.requests.DeleteItemsFromCollectionDbRequest
import org.zotero.android.database.requests.EditNoteDbRequest
import org.zotero.android.database.requests.EmptyTrashDbRequest
import org.zotero.android.database.requests.MarkItemsAsTrashedDbRequest
import org.zotero.android.database.requests.MarkObjectsAsDeletedDbRequest
import org.zotero.android.database.requests.ReadItemDbRequest
import org.zotero.android.database.requests.key
import org.zotero.android.files.FileStore
import org.zotero.android.helpers.GetUriDetailsUseCase
import org.zotero.android.helpers.MediaSelectionResult
import org.zotero.android.helpers.SelectMediaUseCase
import org.zotero.android.pdf.data.PdfReaderArgs
import org.zotero.android.screens.addnote.data.AddOrEditNoteArgs
import org.zotero.android.screens.addnote.data.SaveNoteAction
import org.zotero.android.screens.allitems.AllItemsViewEffect.ShowAddByIdentifierEffect
import org.zotero.android.screens.allitems.AllItemsViewEffect.ShowItemTypePickerEffect
import org.zotero.android.screens.allitems.data.ItemAccessory
import org.zotero.android.screens.allitems.data.ItemCellModel
import org.zotero.android.screens.allitems.data.ItemsError
import org.zotero.android.screens.allitems.data.ItemsFilter
import org.zotero.android.screens.allitems.processor.AllItemsProcessor
import org.zotero.android.screens.allitems.processor.AllItemsProcessorInterface
import org.zotero.android.screens.collectionpicker.data.CollectionPickerArgs
import org.zotero.android.screens.collectionpicker.data.CollectionPickerMode
import org.zotero.android.screens.collectionpicker.data.CollectionPickerMultiResult
import org.zotero.android.screens.collections.data.CollectionsArgs
import org.zotero.android.screens.dashboard.data.ShowDashboardLongPressBottomSheet
import org.zotero.android.screens.filter.data.FilterArgs
import org.zotero.android.screens.filter.data.FilterReloadEvent
import org.zotero.android.screens.filter.data.FilterResult
import org.zotero.android.screens.itemdetails.data.DetailType
import org.zotero.android.screens.itemdetails.data.ItemDetailsArgs
import org.zotero.android.screens.mediaviewer.image.ImageViewerArgs
import org.zotero.android.screens.mediaviewer.video.VideoPlayerArgs
import org.zotero.android.screens.sortpicker.data.SortPickerArgs
import org.zotero.android.sync.Collection
import org.zotero.android.sync.CollectionIdentifier
import org.zotero.android.sync.KeyGenerator
import org.zotero.android.sync.Libraries
import org.zotero.android.sync.Library
import org.zotero.android.sync.LibraryIdentifier
import org.zotero.android.sync.Note
import org.zotero.android.sync.SchemaController
import org.zotero.android.sync.SyncKind
import org.zotero.android.sync.SyncScheduler
import org.zotero.android.sync.Tag
import org.zotero.android.uicomponents.bottomsheet.LongPressOptionItem
import org.zotero.android.uicomponents.singlepicker.SinglePickerArgs
import org.zotero.android.uicomponents.singlepicker.SinglePickerResult
import org.zotero.android.uicomponents.singlepicker.SinglePickerStateCreator
import org.zotero.android.uicomponents.snackbar.SnackbarMessage
import timber.log.Timber
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@HiltViewModel
internal class AllItemsViewModel @Inject constructor(
    private val dbWrapper: DbWrapper,
    private val fileStore: FileStore,
    private val selectMedia: SelectMediaUseCase,
    private val getUriDetailsUseCase: GetUriDetailsUseCase,
    private val schemaController: SchemaController,
    private val syncScheduler: SyncScheduler,
    private val allItemsProcessor: AllItemsProcessor,
    private val dispatchers: Dispatchers,
    private val navigationParamsMarshaller: NavigationParamsMarshaller,
) : BaseViewModel2<AllItemsViewState, AllItemsViewEffect>(AllItemsViewState()),
    AllItemsProcessorInterface {

    private var collection: Collection = Collection(
        identifier = CollectionIdentifier.collection(""),
        name = "",
        itemCount = 0
    )

    private var library: Library = Library(
        identifier = LibraryIdentifier.group(0),
        name = "",
        metadataEditable = false,
        filesEditable = false
    )

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: EventBusConstants.FileWasSelected) {
        if (event.uri != null && event.callPoint == EventBusConstants.FileWasSelected.CallPoint.AllItems) {
            viewModelScope.launch {
                addAttachments(listOf(event.uri))
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(saveNoteAction: SaveNoteAction) {
        if (saveNoteAction.isFromDashboard) {
            viewModelScope.launch {
                saveNote(saveNoteAction.text, saveNoteAction.tags, saveNoteAction.key)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(filterResult: FilterResult) {
        when (filterResult) {
            is FilterResult.enableFilter -> {
                enable(filterResult.filter)
            }
            is FilterResult.disableFilter -> {
                disable(filterResult.filter)
            }
            is FilterResult.tagSelectionDidChange -> {
                tagSelectionDidChange(filterResult.selectedTags)
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(event: LongPressOptionItem) {
        onLongPressOptionsItemSelected(event)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onEvent(result: CollectionPickerMultiResult) {
        add(itemKeys = preSelectedItemKeysToAddToCollection, collectionKeys = result.keys)
    }

    override fun showItemDetailWithDelay(creation: DetailType.creation) {
        viewModelScope.launch {
            delay(800)
            showItemDetail(
                type = creation, library = this@AllItemsViewModel.library
            )
        }
    }

    private fun showItemDetail(type: DetailType, library: Library) {
        ScreenArguments.itemDetailsArgs = ItemDetailsArgs(type, library = library, childKey = null)
        triggerEffect(AllItemsViewEffect.ShowItemDetailEffect)
    }

    fun init(isTablet: Boolean) = initOnce {
        EventBus.getDefault().register(this)
        val args = ScreenArguments.allItemsArgs
        this.collection = args.collection
        this.library = args.library

        val searchTerm = args.searchTerm

        updateState {
            copy(
                searchTerm = searchTerm,
                error = args.error,
                isCollectionTrash = this@AllItemsViewModel.collection.identifier.isTrash,
                isCollectionACollection = this@AllItemsViewModel.collection.identifier.isCollection,
                collectionName = this@AllItemsViewModel.collection.name
            )
        }

        allItemsProcessor.init(
            viewModelScope = viewModelScope,
            allItemsProcessorInterface = this,
            searchTerm = searchTerm
        )

        if (isTablet) {
            initShowFilterArgs()
        }
    }

    override fun show(attachment: Attachment, library: Library) {
        viewModelScope.launch {
            val attachmentType = attachment.type
            when (attachmentType) {
                is Attachment.Kind.url -> {
                    showUrl(attachmentType.url)
                }
                is Attachment.Kind.file -> {
                    val filename = attachmentType.filename
                    val contentType = attachmentType.contentType
                    val file = fileStore.attachmentFile(
                        libraryId = library.identifier,
                        key = attachment.key,
                        filename = filename,
                    )
                    when (contentType) {
                        "application/pdf" -> {
                            showPdf(file = file, key = attachment.key, library = library)
                        }
                        "text/html", "text/plain" -> {
                            val url = file.toUri().toString()
                            val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
                            triggerEffect(AllItemsViewEffect.ShowZoteroWebView(encodedUrl))
                        }
                        else -> {
                            if (contentType.contains("image")) {
                                showImageFile(file)
                            } else if (contentType.contains("video")) {
                                showVideoFile(file)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showVideoFile(file: File) {
        ScreenArguments.videoPlayerArgs = VideoPlayerArgs(Uri.fromFile(file))
        triggerEffect(AllItemsViewEffect.ShowVideoPlayer)
    }

    private fun showImageFile(file: File) {
        ScreenArguments.imageViewerArgs = ImageViewerArgs(Uri.fromFile(file), file.name)
        triggerEffect(AllItemsViewEffect.ShowImageViewer)
    }

    private fun showPdf(file: File, key: String, library: Library) {
        val uri = Uri.fromFile(file)
        val pdfReaderArgs = PdfReaderArgs(
            key = key,
            library = library,
            page = null,
            preselectedAnnotationKey = null,
            uri = uri,
        )
        val params = navigationParamsMarshaller.encodeObjectToBase64(pdfReaderArgs)
        triggerEffect(AllItemsViewEffect.NavigateToPdfScreen(params))
    }

    private fun openFile(file: File, mime: String) {
        triggerEffect(AllItemsViewEffect.OpenFile(file, mime))
    }

    fun onSearch(text: String) {
        updateState {
            copy(searchTerm = text)
        }
        allItemsProcessor.onSearch(text = text)
    }



    override fun updateTagFilter() {
        EventBus.getDefault().post(
            FilterReloadEvent(
                filters = viewState.filters,
                collectionId = this.collection.identifier,
                libraryId = this.library.identifier,
            )
        )
    }

    override fun currentLibrary(): Library {
        return this.library
    }

    override fun currentCollection(): Collection {
        return this.collection
    }

    override fun currentSearchTerm(): String? {
        return viewState.searchTerm
    }

    override fun currentFilters(): List<ItemsFilter> {
        return viewState.filters
    }

    override fun isEditing(): Boolean {
        return viewState.isEditing
    }

    fun onAdd() {
        updateState {
            copy(
                shouldShowAddBottomSheet = true
            )
        }
    }

    fun onAddFile() {
        onAddBottomSheetCollapse()
    }

    fun onAddBottomSheetCollapse() {
        updateState {
            copy(
                shouldShowAddBottomSheet = false
            )
        }
    }

    override fun sendChangesToUi(
        updatedItemCellModels: SnapshotStateList<ItemCellModel>?,
        updatedDownloadingAccessories: SnapshotStateMap<String, ItemCellModel.Accessory?>?
    ) {
        viewModelScope.launch {
            updateState {
                copy(
                    itemCellModels = updatedItemCellModels ?: viewState.itemCellModels,
                    accessoryBeingDownloaded = updatedDownloadingAccessories
                        ?: viewState.accessoryBeingDownloaded
                )
            }
        }

    }

    override fun updateLCE(lce: LCE2) {
        updateState {
            copy(lce = lce)
        }
    }

    override fun showError(error: ItemsError) {
        updateState {
            copy(error = error)
        }
    }

    override fun onCleared() {
        allItemsProcessor.clear()
        EventBus.getDefault().unregister(this)
        super.onCleared()
    }

    private suspend fun addAttachments(urls: List<Uri>) {
        val libraryId = this.library.identifier
        val attachments = mutableListOf<Attachment>()
        for (url in urls) {
            val key = KeyGenerator.newKey()

            val selectionResult = selectMedia.execute(
                uri = url.toString(),
                isValidMimeType = { true }
            )

            val isSuccess = selectionResult is MediaSelectionResult.AttachMediaSuccess
            if (!isSuccess) {
                //TODO parse errors
                continue
            }
            selectionResult as MediaSelectionResult.AttachMediaSuccess

            val original = selectionResult.file.file
            val filename = original.nameWithoutExtension + "." + original.extension
            val contentType =
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(original.extension)!!
            val file = fileStore.attachmentFile(libraryId = libraryId, key = key, filename = filename)
            if (!original.renameTo(file)) {
                Timber.e("can't move file")
                continue
            }
            attachments.add(
                Attachment(
                    type = Attachment.Kind.file(
                        filename = filename,
                        contentType = contentType,
                        location = Attachment.FileLocation.local,
                        linkType = Attachment.FileLinkType.importedFile
                    ),
                    title = filename,
                    key = key,
                    libraryId = libraryId
                )
            )
        }
        if (attachments.isEmpty()) {
            updateState {
                copy(error = ItemsError.attachmentAdding(ItemsError.AttachmentLoading.couldNotSave))
            }
            return
        }
        val collections: Set<String>
        val identifier = this.collection.identifier
        when(identifier) {
            is CollectionIdentifier.collection -> {
                collections = setOf(identifier.key)
            }
            is CollectionIdentifier.search, is CollectionIdentifier.custom -> {
                collections = emptySet()
            }
        }
        val type = schemaController.localizedItemType(ItemTypes.attachment) ?: ""
        val request = CreateAttachmentsDbRequest(attachments = attachments, parentKey = null, localizedType = type, collections = collections, fileStore = fileStore)

        val result = perform(dbWrapper, invalidateRealm = true, request = request).ifFailure {
            Timber.e(it,"ItemsActionHandler: can't add attachment")
            updateState {
                copy(error = ItemsError.attachmentAdding(ItemsError.AttachmentLoading.couldNotSave))
            }
            return
        }
        val failed = result
        if (failed.isEmpty()) {
            return
        }
        updateState {
            copy(error = ItemsError.attachmentAdding(ItemsError.AttachmentLoading.someFailed(failed.map { it.second })))
        }

    }

    fun onAddNote() {
        showNoteCreation(title = null, libraryId = this.library.identifier)
    }

    fun onAddManually(title: String) {
        ScreenArguments.singlePickerArgs = SinglePickerArgs(
            singlePickerState = SinglePickerStateCreator.create(
                selected = "",
                schemaController
            ),
            showSaveButton = false,
            title = title,
            callPoint = SinglePickerResult.CallPoint.AllItemsShowItem
        )
        triggerEffect(ShowItemTypePickerEffect)

    }

    private fun showNoteCreation(title: AddOrEditNoteArgs.TitleData?, libraryId: LibraryIdentifier) {
        ScreenArguments.addOrEditNoteArgs = AddOrEditNoteArgs(
            text = "",
            tags = listOf(),
            title = title,
            key = KeyGenerator.newKey(),
            libraryId = libraryId,
            readOnly = false,
            isFromDashboard = true,
        )
        triggerEffect(AllItemsViewEffect.ShowAddOrEditNoteEffect)
    }

    private fun showItemDetail(item: RItem) {
        when (item.rawType) {
            ItemTypes.note -> {
                val note = Note.init(item = item)
                if (note == null) {
                    return
                }
                val tags = item.tags!!.map({ Tag(tag = it) })
                val library = this.library
                ScreenArguments.addOrEditNoteArgs = AddOrEditNoteArgs(
                    text = note.text,
                    tags = tags,
                    title = null,
                    libraryId = library.identifier,
                    readOnly = !library.metadataEditable,
                    key = note.key,
                    isFromDashboard = true
                )
                triggerEffect(AllItemsViewEffect.ShowAddOrEditNoteEffect)
            }
            else -> {
                ScreenArguments.itemDetailsArgs = ItemDetailsArgs(
                    DetailType.preview(key = item.key),
                    library = this.library,
                    childKey = null
                )
                triggerEffect(AllItemsViewEffect.ShowItemDetailEffect)
            }
        }
    }

    private suspend fun saveNote(text: String, tags: List<Tag>, key: String) = withContext(dispatchers.io) {
        val note = Note(key = key, text = text, tags = tags)
        val libraryId = this@AllItemsViewModel.library.identifier
        var collectionKey: String? = null

        val identifier = this@AllItemsViewModel.collection.identifier
        when (identifier) {
            is CollectionIdentifier.collection ->
                collectionKey = identifier.key
            is CollectionIdentifier.custom, is CollectionIdentifier.search ->
                collectionKey = null
        }

        try {
            dbWrapper.realmDbStorage.perform(
                EditNoteDbRequest(
                    note = note,
                    libraryId = libraryId
                )
            )
        } catch (e: Throwable) {
            if (e is DbError.objectNotFound) {
                val request = CreateNoteDbRequest(
                    note = note,
                    localizedType = (schemaController.localizedItemType(
                        ItemTypes.note
                    ) ?: ""),
                    libraryId = libraryId,
                    collectionKey = collectionKey,
                    parentKey = null
                )
                dbWrapper.realmDbStorage.perform(request = request, invalidateRealm = true)
            } else {
                Timber.e(e)
            }
        }
    }

    private fun showMetadata(item: RItem) {
        showItemDetail(item)
//        resetActiveSearch() //TODO implement
    }

    fun onItemTapped(item: ItemCellModel) {
        if (viewState.isEditing) {
            val selectedKeys = viewState.selectedKeys!!
            val isCurrentlySelected = selectedKeys.contains(item.key)
            updateState {
                copy(
                    selectedKeys = if (isCurrentlySelected) {
                        selectedKeys.remove(item.key)
                    } else {
                        selectedKeys.add(item.key)
                    }
                )
            }
            return
        }

        val accessory = allItemsProcessor.getItemAccessoryByKey(item.key)
        if (accessory == null) {
            showMetadata(allItemsProcessor.getResultByKey(item.key))
            return
        }

        viewModelScope.launch {
            when (accessory) {
                is ItemAccessory.attachment -> {
                    val parentKey = if (item.key == accessory.attachment.key) null else item.key
                    allItemsProcessor.open(attachment = accessory.attachment, parentKey = parentKey)
                }
                is ItemAccessory.doi -> showDoi(accessory.doi)
                is ItemAccessory.url -> showUrl(url = accessory.url)
            }
        }
    }

    fun onAccessoryTapped(key:String) {
        showMetadata(allItemsProcessor.getResultByKey(key))
    }

    private fun showDoi(doi: String) {
        val url = "https://doi.org/$doi"
        triggerEffect(AllItemsViewEffect.OpenWebpage(Uri.parse(url)))
    }

    private suspend fun showUrl(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != null && uri.scheme != "http" && uri.scheme != "https") {
            val mimeType = getUriDetailsUseCase.getMimeType(url)!!
            triggerEffect(AllItemsViewEffect.OpenFile(uri.toFile(), mimeType))
        } else {
            triggerEffect(AllItemsViewEffect.OpenWebpage(uri))
        }
    }

    fun showSortPicker() {
        ScreenArguments.sortPickerArgs = SortPickerArgs(
            sortType = allItemsProcessor.getSortType()
        )
        triggerEffect(AllItemsViewEffect.ShowSortPickerEffect)
    }


    private fun enable(filter: ItemsFilter) {
        if (viewState.filters.contains(filter)) {
            return
        }
        if (filter is ItemsFilter.tags) {
            updateState {
                copy(
                    filters = viewState.filters.filter { it !is ItemsFilter.tags }
                )
            }
        }
        updateState {
            copy(
                filters = viewState.filters + filter
            )
        }
        allItemsProcessor.filter(searchTerm = viewState.searchTerm, filters = viewState.filters)
    }

    private fun disable(filter: ItemsFilter) {
        val index = viewState.filters.indexOf(filter)
        if (index == -1) {
            return
        }
        updateState {
            copy(
                filters = viewState.filters - filter
            )
        }
        allItemsProcessor.filter(searchTerm = viewState.searchTerm, filters = viewState.filters)
    }

    fun showFilters() {
        initShowFilterArgs()
        triggerEffect(AllItemsViewEffect.ShowFilterEffect)
    }

    private fun initShowFilterArgs() {
        val selectedTags =
            viewState.filters.filterIsInstance<ItemsFilter.tags>().flatMap { it.tags }.toSet()
        ScreenArguments.filterArgs = FilterArgs(
            filters = viewState.filters,
            collectionId = this.collection.identifier,
            libraryId = this.library.identifier,
            selectedTags = selectedTags
        )
    }

    private fun onLongPressOptionsItemSelected(longPressOptionItem: LongPressOptionItem) {
        viewModelScope.launch {
            when (longPressOptionItem) {
                is LongPressOptionItem.MoveToTrashItem -> {
                    trashItems(setOf(longPressOptionItem.item.key))
                }
                is LongPressOptionItem.Download -> {
                    allItemsProcessor.downloadAttachments(setOf(longPressOptionItem.item.key))
                }
                is LongPressOptionItem.RemoveDownload -> {
                    allItemsProcessor.removeDownloads(setOf(longPressOptionItem.item.key))
                }
                is LongPressOptionItem.Duplicate -> {
                    loadItemForDuplication(longPressOptionItem.item.key)
                }
                is LongPressOptionItem.AddToCollection -> {
                    showCollectionPicker(setOf(longPressOptionItem.item.key))
                }
                is LongPressOptionItem.RemoveFromCollection -> {
                    showRemoveFromCollectionQuestion(setOf(longPressOptionItem.item.key))
                }
                is LongPressOptionItem.CreateParentItem -> {
                    createParent(longPressOptionItem.item)
                }
                is LongPressOptionItem.TrashDelete -> {
                    showDeleteItemsConfirmation(
                        setOf(longPressOptionItem.item.key)
                    )
                }
                is LongPressOptionItem.TrashRestore -> {
                    set(trashed = false, setOf(longPressOptionItem.item.key))
                }
                else -> {}
            }
        }
    }

    private var preSelectedItemKeysToAddToCollection: Set<String> = emptySet()

    private fun showCollectionPicker(selectedItemKeys: Set<String>) {
        preSelectedItemKeysToAddToCollection = selectedItemKeys
        ScreenArguments.collectionPickerArgs = CollectionPickerArgs(
            mode = CollectionPickerMode.multiple,
            libraryId = this.library.identifier,
            excludedKeys = emptySet(),
            selected = emptySet()
        )
        triggerEffect(AllItemsViewEffect.ShowCollectionPickerEffect)
    }

    private fun createParent(item: RItem) {
        val key = item.key
        val accessory = allItemsProcessor.getItemAccessoryByKey(key)?: return
        val attachment = (accessory as? ItemAccessory.attachment)?.attachment ?:return
        var collectionKey: String? = null
        when(this.collection.identifier) {
            is CollectionIdentifier.collection ->
            collectionKey = this.collection.identifier.keyGet
            else -> {
                //no-op
            }
        }
        showItemDetail(
            DetailType.creation(
                type = ItemTypes.document,
                child = attachment,
                collectionKey = collectionKey
            ), library = this.library
        )
    }

    fun onItemLongTapped(key: String) {
        val item = allItemsProcessor.getResultByKey(key)
        if (this.collection.identifier.isTrash) {
            EventBus.getDefault().post(
                ShowDashboardLongPressBottomSheet(
                    title = item.displayTitle,
                    longPressOptionItems = listOf(
                        LongPressOptionItem.TrashRestore(item),
                        LongPressOptionItem.TrashDelete(item)
                    )
                )
            )
            return
        }

        val actions = mutableListOf<LongPressOptionItem>()

        if (item.rawType == ItemTypes.attachment && item.parent == null) {
            actions.add(LongPressOptionItem.CreateParentItem(item))
        }

        val accessory = allItemsProcessor.getItemAccessoryByKey(item.key)
        if (accessory != null) {
            val location = accessory.attachmentGet?.location
            if (location != null) {
                when(location) {
                    Attachment.FileLocation.local -> {
                        actions.add(LongPressOptionItem.RemoveDownload(item))
                    }
                    Attachment.FileLocation.remote -> {
                        actions.add(LongPressOptionItem.Download(item))
                    }
                    Attachment.FileLocation.localAndChangedRemotely -> {
                        actions.add(LongPressOptionItem.Download(item))
                        actions.add(LongPressOptionItem.RemoveDownload(item))
                    }
                    Attachment.FileLocation.remoteMissing -> {
                        //no-op
                    }
                }
            }
        }

        val identifier = this.collection.identifier

        actions.add(LongPressOptionItem.AddToCollection(item))

        if (identifier is CollectionIdentifier.collection && item.collections?.where()?.key(identifier.key)?.findFirst() != null) {
            actions.add(LongPressOptionItem.RemoveFromCollection(item))
        }

        if (item.rawType != ItemTypes.note && item.rawType != ItemTypes.attachment) {
            actions.add(LongPressOptionItem.Duplicate(item))
        }

        actions.add(LongPressOptionItem.MoveToTrashItem(item))

        EventBus.getDefault().post(
            ShowDashboardLongPressBottomSheet(
                title = item.displayTitle,
                longPressOptionItems = actions
            )
        )
    }

    fun delete(keys: Set<String>) {
        viewModelScope.launch {
            perform(
                dbWrapper = dbWrapper,
                request = MarkObjectsAsDeletedDbRequest(
                    clazz = RItem::class,
                    keys = keys.toList(),
                    libraryId = this@AllItemsViewModel.library.identifier
                )
            ).ifFailure {
                Timber.e(it, "AllItemsViewModel: can't delete items")
                updateState {
                    copy(error = ItemsError.deletion)
                }
                return@launch
            }
        }

    }

    private suspend fun trashItems(keys: Set<String>) {
        set(trashed = true, keys = keys)
    }

    private suspend fun set(trashed: Boolean, keys: Set<String>) {
        val request = MarkItemsAsTrashedDbRequest(
            keys = keys.toList(),
            libraryId = this.library.identifier,
            trashed = trashed
        )
        perform(dbWrapper = dbWrapper, request = request).ifFailure { error ->
            Timber.e(error, "ItemsStore: can't trash items")
            updateState {
                copy(
                    error = ItemsError.deletion,
                )
            }
            return
        }
    }

    private fun loadItemForDuplication(key: String) {
        val request = ReadItemDbRequest(libraryId = this.library.identifier, key = key)

        try {
            val item = dbWrapper.realmDbStorage.perform(request = request)
            stopEditing()
            showItemDetail(DetailType.duplication(itemKey = item.key, collectionKey = this.collection.identifier.keyGet), library = this.library)
        } catch (error: Exception) {
            Timber.e(error, "ItemsActionHandler: could not read item")
            updateState {
                copy(error = ItemsError.duplicationLoading)
            }
        }
    }

    private fun startEditing() {
        updateState {
            copy(
                selectedKeys = persistentSetOf(),
            )
        }
    }

    private fun stopEditing() {
        updateState {
            copy(
                selectedKeys = null,
            )
        }
    }

    fun onSelect() {
        startEditing()
    }

    fun onDone() {
        stopEditing()
    }

    fun toggleSelectionState() {
        if (!viewState.areAllSelected) {
            val allItemsKeys = viewState.itemCellModels.map { it.key }.toPersistentSet()
            updateState {
                copy(selectedKeys = allItemsKeys)
            }
        } else{
            updateState {
                copy(selectedKeys = persistentSetOf())
            }
        }
    }

    fun onTrash() {
        viewModelScope.launch {
            trashItems(getSelectedKeys())
        }
    }

    fun onRestore() {
        viewModelScope.launch {
            set(trashed = false, keys = getSelectedKeys())
        }
    }

    fun onDelete() {
        showDeleteItemsConfirmation(getSelectedKeys())
    }

    fun onEmptyTrash() {
        updateState {
            copy(
                error = ItemsError.deleteConfirmationForEmptyTrash,
            )
        }
    }

    private fun showDeleteItemsConfirmation(itemsKeys: Set<String>) {
        updateState {
            copy(
                error = ItemsError.deleteConfirmationForItems(itemsKeys),
            )
        }
    }

    fun navigateToCollections() {
        ScreenArguments.collectionsArgs = CollectionsArgs(libraryId = fileStore.getSelectedLibrary(), fileStore.getSelectedCollectionId())
        triggerEffect(AllItemsViewEffect.ShowCollectionsEffect)
    }

    fun onDismissDialog() {
        updateState {
            copy(
                error = null,
            )
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            perform(
                dbWrapper = dbWrapper,
                request = EmptyTrashDbRequest(libraryId = this@AllItemsViewModel.library.identifier)
            ).ifFailure {
                Timber.e(it, "AllItemsViewModel: can't empty trash")
                return@launch
            }
        }
    }

    fun startSync() {
        if (viewState.isRefreshing) {
            return
        }
        updateState {
            copy(isRefreshing = true)
        }
        this.syncScheduler.request(
            type = SyncKind.ignoreIndividualDelays, libraries = Libraries.specific(
                listOf(this.library.identifier)
            )
        )
        viewModelScope.launch {
            //TODO monitor sync progress and don't sync when sync is in progress
            delay(3000)
            updateState {
                copy(isRefreshing = false)
            }
        }
    }

    private fun tagSelectionDidChange(selected: Set<String>) {
        if (selected.isEmpty()) {
            val tags = viewState.tagsFilter
            if (tags != null) {
                disable(ItemsFilter.tags(tags))
            }
        } else {
            enable(ItemsFilter.tags(selected))
        }
    }

    fun onAddToCollection() {
       showCollectionPicker(getSelectedKeys())
    }

    fun showRemoveFromCollectionQuestion() {
        showRemoveFromCollectionQuestion(getSelectedKeys())
    }

    fun showRemoveFromCollectionQuestion(itemsKeys: Set<String>) {
        updateState {
            copy(
                error = ItemsError.showRemoveFromCollectionQuestion(
                    itemsKeys
                )
            )
        }
    }

    fun deleteItemsFromCollection(keys: Set<String>) {
        val identifier = this.collection.identifier
        if (identifier !is CollectionIdentifier.collection) {
            return
        }

        val request = DeleteItemsFromCollectionDbRequest(
            collectionKey = identifier.key,
            itemKeys = keys,
            libraryId = this.library.identifier
        )
        viewModelScope.launch {
            perform(dbWrapper, request = request).ifFailure {
                Timber.e(it, "ItemsStore: can't delete items")
                updateState {
                    copy(error = ItemsError.deletionFromCollection)
                }
                return@launch
            }
        }
    }

    private fun add(itemKeys: Set<String>, collectionKeys: Set<String>) {
        val request = AssignItemsToCollectionsDbRequest(
            collectionKeys = collectionKeys,
            itemKeys = itemKeys,
            libraryId = this.library.identifier
        )

        viewModelScope.launch {
            perform(dbWrapper, request = request).ifFailure {
                Timber.e(it, "ItemsStore: can't assign collections to items")
                updateState {
                    copy(error = ItemsError.collectionAssignment)
                }
                return@launch
            }
        }
    }
    private fun getSelectedKeys(): Set<String> {
        return viewState.selectedKeys ?: emptySet()
    }

    fun onAddByIdentifier() {
        triggerEffect(ShowAddByIdentifierEffect)
    }

}

internal data class AllItemsViewState(
    val lce: LCE2 = LCE2.Content,
    val snackbarMessage: SnackbarMessage? = null,
    val itemCellModels: SnapshotStateList<ItemCellModel> = mutableStateListOf(),
    val accessoryBeingDownloaded: SnapshotStateMap<String, ItemCellModel.Accessory?> = mutableStateMapOf(),
    val selectedKeys: PersistentSet<String>? = null,
    val error: ItemsError? = null,
    val shouldShowAddBottomSheet: Boolean = false,
    val searchTerm: String? = null,
    val isRefreshing: Boolean = false,
    val filters: List<ItemsFilter> = emptyList(),
    val isCollectionTrash: Boolean = false,
    val isCollectionACollection: Boolean = false,
    val collectionName: String = ""
) : ViewState {
    val tagsFilter: Set<String>?
        get() {
            val tagFilter = this.filters.firstOrNull { filter ->
                filter is ItemsFilter.tags
            }

            if (tagFilter == null || tagFilter !is ItemsFilter.tags) {
                return null
            }
            return tagFilter.tags
        }
    val isEditing : Boolean get() {
        return selectedKeys != null
    }
    fun isSelected(key: String): Boolean {
        return selectedKeys?.contains(key) == true
    }

    fun isAnythingSelected(): Boolean {
        return selectedKeys?.isNotEmpty() == true
    }
    val areAllSelected get(): Boolean {
        val size = itemCellModels.size
        return (selectedKeys?.size ?: 0) == size
    }

    fun getAccessoryForItem(itemKey: String): ItemCellModel.Accessory? {
        val beingDownloadedAccessory = accessoryBeingDownloaded[itemKey]
        if (beingDownloadedAccessory != null) {
            return beingDownloadedAccessory
        }
        return itemCellModels.firstOrNull { it.key == itemKey }?.accessory
    }
}

internal sealed class AllItemsViewEffect : ViewEffect {
    object ShowCollectionsEffect: AllItemsViewEffect()
    object ShowItemDetailEffect: AllItemsViewEffect()
    object ShowAddOrEditNoteEffect: AllItemsViewEffect()
    object ShowItemTypePickerEffect : AllItemsViewEffect()
    object ShowAddByIdentifierEffect : AllItemsViewEffect()
    object ShowSortPickerEffect : AllItemsViewEffect()
    object ShowCollectionPickerEffect: AllItemsViewEffect()
    object ShowFilterEffect : AllItemsViewEffect()
    data class OpenWebpage(val uri: Uri) : AllItemsViewEffect()
    data class OpenFile(val file: File, val mimeType: String) : AllItemsViewEffect()
    data class ShowZoteroWebView(val url: String): AllItemsViewEffect()
    object ShowVideoPlayer : AllItemsViewEffect()
    object ShowImageViewer : AllItemsViewEffect()
    data class NavigateToPdfScreen(val params: String) : AllItemsViewEffect()
    object ScreenRefresh : AllItemsViewEffect()
}
