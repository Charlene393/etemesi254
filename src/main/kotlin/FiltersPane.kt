import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import components.*
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable


@Composable
fun histogramPane(appCtx: AppContext) {
    CollapsibleBox("Histogram", appCtx.showStates.showHistogram, onTopRowClicked = {
        appCtx.showStates.showHistogram = appCtx.showStates.showHistogram.not()
    }) {
        Box() {
            HistogramChart(appCtx)
        }
    }
}


@Composable
fun FiltersPane(appCtx: AppContext) {

    Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier.fillMaxSize().defaultMinSize(80.dp)
    ) {
        if (appCtx.openPane != RightPaneOpened.None) {
            Surface(
                color = MaterialTheme.colors.background,
                modifier = Modifier.fillMaxWidth().padding(end = 40.dp)
            ) {
                when (appCtx.openPane) {
                    RightPaneOpened.None -> Box {}
                    RightPaneOpened.InformationPanel -> InformationPanel(appCtx)
                    RightPaneOpened.FiltersPanel -> ImageFiltersPane(appCtx)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxHeight().requiredWidth(50.dp)
            //.border(2.dp, MaterialTheme.colors.onBackground, shape = RectangleShape)
        ) {

            Divider(color = Color(0xFF_29_29_29), modifier = Modifier.fillMaxHeight().width(1.dp))

            Column {
                IconButton({
                    if (appCtx.openPane == RightPaneOpened.InformationPanel) {
                        appCtx.openPane = RightPaneOpened.None
                    } else {
                        appCtx.openPane = RightPaneOpened.InformationPanel
                    }

                }, enabled = appCtx.imageIsLoaded) {
                    Icon(
                        painter = painterResource("info-svgrepo.svg"),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                }

                IconButton({
                    if (appCtx.openPane == RightPaneOpened.FiltersPanel) {
                        appCtx.openPane = RightPaneOpened.None
                    } else {
                        appCtx.openPane = RightPaneOpened.FiltersPanel
                    }

                }, enabled = appCtx.imageIsLoaded) {
                    Icon(
                        painter = painterResource("image-edit-svgrepo-com.svg"),
                        contentDescription = null,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

        }
    }
}


@Composable
fun ExifMetadataPane(appCtx: AppContext) {

    val exif: Map<String, String> = appCtx.image.inner.exifMetadata()


    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {


        if (exif.isNotEmpty()) {
            Divider()
            Text(
                "Exif Metadata",
                modifier = Modifier.padding(top = 10.dp, bottom = 10.dp),
                style = MaterialTheme.typography.h5
            )
            //Divider()
        }
        for ((k, v) in exif) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(all = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    k,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 10.dp),
                    style = TextStyle(fontSize = TextUnit(14F, TextUnitType.Sp))
                )
                Text(
                    v,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = TextStyle(fontSize = TextUnit(14F, TextUnitType.Sp))
                )
            }
            Divider()
        }
    }
}

@Composable
fun InformationPanel(appCtx: AppContext) {
    val scrollState = rememberLazyListState()

    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 10.dp)) {
        LazyColumn(state = scrollState) {
            item {
                ImageInformationComponent(appCtx)
            }
            item {
                ExifMetadataPane(appCtx)
            }
        }

        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(
                scrollState = scrollState
            )
        )
    }
}

@Composable
fun ImageFiltersPane(appCtx: AppContext) {
    val density = LocalDensity.current;

    // We want to have items to reorder in the panels/pane. but then we can't have compose return
    // a list of widgets. So we use this
    //
    // The trick is that we store a separate ordering list and then on drawing, loop on that list
    var enumOrdering = remember { FiltersPaneOrdering.values().toMutableList() };

    // it may happen that the enumOrdering changed but compose doesn't rerender
    // the components, this is because it seems to compose that
    // the box didn't change
    //
    // So we use this to force compose to recompose via our cool
    // modifyOnChange extension
    var orderChanged by remember { mutableStateOf(false) };

    val scrollState = rememberLazyListState()

    val state = rememberReorderableLazyListState(onMove = { from, to ->
        enumOrdering = enumOrdering.apply {

            this.add(to.index, removeAt(from.index))
            // tell box to recompose
            orderChanged = orderChanged.not()

        }
    })

    AnimatedVisibility(
        visible = appCtx.openPane == RightPaneOpened.FiltersPanel,
        enter = slideInHorizontally { with(density) { +40.dp.roundToPx() } },
        exit = slideOutHorizontally { with(density) { +400.dp.roundToPx() } },
        modifier = Modifier.fillMaxHeight()
    ) {

        Surface(
            modifier = Modifier.modifyOnChange(orderChanged)
                .fillMaxHeight().padding(horizontal = 5.dp)
        ) {
            // provides a scrollbar

            LazyColumn(
                modifier = Modifier.padding(10.dp)
                    .reorderable(state)
                    .detectReorderAfterLongPress(state)
                    .fillMaxHeight(),
                state = state.listState,

                ) {

                items(enumOrdering.size) {
                    when (val item = enumOrdering[it]) {

                        FiltersPaneOrdering.LightFilters -> {
                            ReorderableItem(state, key = item, index = it) {
                                LightFiltersComponent(appCtx)
                            }
                        }

                        FiltersPaneOrdering.OrientationFilters -> {
                            ReorderableItem(
                                state,
                                key = item,
                                index = it
                            ) { OrientationFiltersComponent(appCtx) }
                        }

                        FiltersPaneOrdering.HistogramFilters -> {
                            ReorderableItem(
                                state,
                                key = item,
                                index = it
                            ) {
                                histogramPane(appCtx)
                            }
                        }

                        FiltersPaneOrdering.Levels -> {
                            ReorderableItem(state, key = item, index = it) {
                                LevelsFiltersComponent(appCtx)
                            }
                        }
                    }
                }
            }

//            VerticalScrollbar(
//                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
//                adapter = rememberScrollbarAdapter(
//                    scrollState = scrollState
//                )
//            )
        }
    }
}