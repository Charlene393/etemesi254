import androidx.compose.runtime.*
import components.ScalableState
import events.ExternalNavigationEventBus
import history.HistoryOperations
import history.HistoryOperationsEnum
import history.HistoryResponse
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.skia.Bitmap
import java.io.File
import java.nio.ByteBuffer

/**
 * A buffer used by native methods to write pixels to
 * where java can understand
 *
 * This is used to tie zune-image to java to skia since we can't tie
 * native memory just like that (and compose skia doesn't support native memory locations)
 *
 * We share this amongst multiple images and adjust size to be enough to hold interleaved pixels for the image
 * (means the buffer can be larger than image pixels, but never smaller)
 *
 * Since we share this amongst multiple images which can be called from multiple threads, we protect it with a mutex
 * */
class SharedBuffer {
    /**
     * The sharedBuffer is used from multiple threads, which means it may happen that two threads
     * write to this which is how you loose sleep.
     *
     * So this protects us, before accessing sharedBuffer and nativeBuffer, lock this mutex
     * */
    val mutex: Mutex = Mutex()

    /**
     * A bytearray that can hold image pixels
     *
     * This is used by skia to read image pixels written by the zune-image
     * */
    var sharedBuffer: ByteArray = ByteArray(0)

    /**
     * A bytebuffer used by zune-image to write pixels
     *
     * This is allocated via ByteBuffer.allocateDirect so that it can be used via
     * jni otherwise the native function will raise an exception
     * */
    var nativeBuffer: ByteBuffer = ByteBuffer.allocate(1)

}

/**
 * Image filter values
 *
 * This contains the current image filter values such as brightness, contrast
 * e.t.c  and are modified by image filters
 * */
class FilterValues {
    var contrast by mutableStateOf(0F)
    var brightness by mutableStateOf(0F)
    var gamma by mutableStateOf(0F)
    var exposure by mutableStateOf(0F)
    var boxBlur by mutableStateOf(0L)
    var gaussianBlur by mutableStateOf(0L)
    var stretchContrastRange: MutableState<ClosedFloatingPointRange<Float>> = mutableStateOf(0F..256.0F)
    var hue by mutableStateOf(0f)
    var saturation by mutableStateOf(1f)
    var lightness by mutableStateOf(1f)
    var medianBlur by mutableStateOf(0L)
    var bilateralBlur by mutableStateOf(0L)
}

/**
 * Image group details
 *
 * This contains the image itself ,filter values, history, zoom state etc
 * */
class ImageContext(image: ZilBitmap) {
    var filterValues by mutableStateOf(FilterValues())
    var history by mutableStateOf(HistoryOperations())

    /**Canvas for which we use for drawing operations */
    var canvasBitmap = Bitmap()


    var image = mutableListOf(image)
    var operationsMutex: Mutex = Mutex()
    var imageIsLoaded by mutableStateOf(false)
    var zoomState by mutableStateOf(ScalableState())
    var imageModified by mutableStateOf(false)


    // the lock is an interesting one
    // we may get image  buffer when we acquire but then another thread
    // invalidates it, e.g. think a very fast operation that ends up calling
    //  allocPixels while we are drawing the image to screen , this would inadvertently end up seg-faulting
    // with the error J 3394  org.jetbrains.skia.ImageKt._nMakeFromBitmap(J)J (0 bytes) @ 0x00007fa518e19061 [0x00007fa518e19020+0x0000000000000041]
    //
    // or if caught early cause a null pointer
    //
    // The solution is to protect skia operations with a mutex, to force a synchronization,
    // in that no two skia operations can be said to run concurrently
    // that's the work of the mutex
    //
    // This is separate from the operations mutex because were we to use that, we can't do zooming and panning when
    // an operation is underway
    //
    val protectSkiaMutex = Mutex();

    init {
        image.prepareNewFile(canvasBitmap)
    }

    /**
     * Current image
     *
     * @param response: If response is the filter is the same as the previous, we pop the last item
     * NOTE: It is only read and manipulate this only after locking the
     * operations mutex, otherwise bad things will happen (race conditions + native code)
     * */
    fun currentImage(response: HistoryResponse): ZilBitmap {
        // peek into history to see if we need to create a new image, add it to the stack and return it or
        // if the operation has a trivial undo, just let it be
        // we are assured that history just pushed this operation since we require HistoryResponse
        // as a parameter
        if (!history.getHistory().last().trivialUndo() && response != HistoryResponse.DummyOperation) {
            // not simple to undo, so back up what we have
            val lastImage = image.last().clone()
            image.add(lastImage)
        }

        return image.last()
    }

    fun imageToDisplay(): ZilBitmap {
        return image.last()
    }

    fun resetStates(newImage: ZilBitmap) {
        filterValues = FilterValues()
        history = HistoryOperations()
        image = mutableListOf(newImage)
        image[0].writeToCanvas(canvasBitmap, protectSkiaMutex)
        imageModified = !imageModified
        imageIsLoaded = false
    }
}

class AppContext {

    /**
     * Contains possible paths that may be images, useful for
     * left and right movement
     * */
    var paths: MutableList<File> = mutableListOf()

    /**
     * Contains current position for keeping track
     * of what is next and previous in left,right navigation
     * */
    var pathPosition = 0;

    /**
     * Contains information about what to show when the decision is
     * a true or false decision, e.g. whether to show a dialog box or not
     * */
    var showStates by mutableStateOf(ShowModifiers())

    /**
     * Contains text for the bottom status
     * */
    var bottomStatus by mutableStateOf("")

    /**
     * External navigations, for passing keyboard events to compose
     * */
    var externalNavigationEventBus by mutableStateOf(ExternalNavigationEventBus())

    /**
     * Current image file we are displaying
     * */
    var imFile by mutableStateOf(File(""))

    /**
     * Current directory we are showing on the directory picker
     * */
    var rootDirectory by mutableStateOf("/")


    var recomposeWidgets by mutableStateOf(RecomposeWidgets())

    /**
     * Contains each image specific states, each image can be seen as a file+ info about it
     * and the information includes the image filter states, image history, image details etc
     * */
    private var imageSpecificStates: LinkedHashMap<File, ImageContext> = LinkedHashMap()

    /**
     * Contains information on which tab the user is currently on
     * Each tab can be a different image
     * */
    var tabIndex by mutableStateOf(0)

    /**
     * Contains information on what right pane was opened
     * when None, indicates no pane is currently opened
     * */
    var openedRightPane by mutableStateOf(RightPaneOpened.None)

    /**
     * Contains information on what left pane was opened,
     * when None, indicates no pane is opened
     * */
    var openedLeftPane by mutableStateOf(LeftPaneOpened.None)

    /**
     * Layout of the image space, can either be single paned i.e. one image
     * or two paned showing unedited image + edited image
     * */
    var imageSpaceLayout by mutableStateOf(ImageSpaceLayout.SingleLayout)


    /**
     *  Contains a shared buffer used by images when we want to write
     *
     *  The image layout is usually ZilBitmap->Skia, but since I can't hand
     *  skia a native memory pointer, we need an intermediate buffer
     *  ZilBitmap->ByteBuffer->Skia, skia will set of to write its own native
     *  memory which it will then use, ignoring our buffer,
     *  this means the buffer can be reused multiple times by separate image threads
     *
     *  So it's here to facilitate that.
     *
     *  When an image wants to display it's output it's going to lock this shared buffer, write to skia and finally
     *  unlock, skia will read its pointer, allocate what it needs and do it's internal shenanigans
     *
     * */
    var sharedBuffer: SharedBuffer = SharedBuffer()


    /**
     * Initialize image details
     *
     * If the image was already loaded, we preserve some details such as zoom state
     * */
    fun initializeImageSpecificStates(image: ZilBitmap) {
        if (imageSpecificStates.containsKey(imFile)) {
            // preserve things like zoom even when reloading
            imageSpecificStates[imFile]?.resetStates(image);
        } else {
            imageSpecificStates[imFile] = ImageContext(image)
        }

        // move the tab index to the newly loaded tab
        imageSpecificStates.asSequence().forEachIndexed { idx, it ->
            if (it.key == imFile) {
                tabIndex = idx
            }
        }
        broadcastImageChange()
    }


    fun initializeImageChange() {
        showStates.showTopLinearIndicator = true

    }

    fun broadcastImageChange() {
        // tell whoever is listening to this to rebuild
        recomposeWidgets.rerunHistogram = !recomposeWidgets.rerunHistogram
        recomposeWidgets.rerunImageSpecificStates = !recomposeWidgets.rerunImageSpecificStates
        showStates.showTopLinearIndicator = false

    }

    fun getHistory(): HistoryOperations? {

        return imageSpecificStates[imFile]?.history

    }

    fun imageIsLoaded(): Boolean {
        if (imFile == File("")) {
            return false
        }
        if (imageSpecificStates[imFile] == null) {
            return false
        }
        return imageSpecificStates[imFile]!!.imageIsLoaded
    }

    fun setImageIsLoaded(yes: Boolean) {
        imageSpecificStates[imFile]!!.imageIsLoaded = yes
    }

    fun appendToHistory(newValue: HistoryOperationsEnum, value: Any? = null): HistoryResponse {

        return imageSpecificStates[imFile]!!.history.addHistory(newValue, value)
    }

    fun resetHistory() {
        imageSpecificStates[imFile]?.history?.reset()
    }

    fun imageFilterValues(): FilterValues? {
        return imageSpecificStates[imFile]?.filterValues
    }

    fun imageStates(): LinkedHashMap<File, ImageContext> {
        return imageSpecificStates
    }

    /**
     * Return the context of the currently displayed image
     * */
    fun currentImageContext(): ImageContext? {
        return imageSpecificStates[imFile]
    }

    fun operationIsOngoing(): Boolean {
        return showStates.showTopLinearIndicator
    }
//
//    fun getImage(): ZilBitmap {
//
//        // if this is null it means the initializer didn't initialize the image
//        return imageSpecificStates[imFile]!!.currentImage()
//    }

}


class RecomposeWidgets {
    var rerunHistogram by mutableStateOf(false)
    var rerunImageSpecificStates by mutableStateOf(false)

}