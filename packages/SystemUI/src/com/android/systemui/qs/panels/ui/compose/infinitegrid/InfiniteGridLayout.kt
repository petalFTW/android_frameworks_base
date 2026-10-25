/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.ui.compose.infinitegrid

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.util.fastMap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.animation.scene.ContentScope
import com.android.systemui.common.ui.compose.PagerDots
import com.android.systemui.compose.modifiers.sysuiResTag
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.grid.ui.compose.VerticalSpannedGrid
import com.android.systemui.haptics.msdl.qs.TileHapticsViewModelFactoryProvider
import com.android.systemui.lifecycle.rememberViewModel
import com.android.systemui.qs.flags.QSMaterialExpressiveTiles
import com.android.systemui.qs.panels.shared.model.SizedTileImpl
import com.android.systemui.qs.panels.ui.viewmodel.PaginatableViewModel.Companion.splitInRows
import com.android.systemui.qs.panels.ui.compose.ButtonGroupGrid
import com.android.systemui.qs.panels.ui.compose.EditTileListState
import com.android.systemui.qs.panels.ui.compose.PaginatableGridLayout
import com.android.systemui.qs.panels.ui.compose.TileListener
import com.android.systemui.qs.panels.ui.compose.bounceableInfo
import com.android.systemui.qs.panels.ui.viewmodel.BounceableTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.DetailsViewModel
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.IconTilesViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InFirstPageViewModel
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.petalos.LocalPetalMediaRow
import com.android.systemui.petalos.PetalQsSkin
import com.android.systemui.petalos.petalQsTileEntrance
import com.android.systemui.petalos.rememberPetalQsReveal
import com.android.systemui.res.R
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SysUISingleton
class InfiniteGridLayout
@Inject
constructor(
    private val detailsViewModel: DetailsViewModel,
    private val iconTilesViewModel: IconTilesViewModel,
    override val viewModelFactory: InfiniteGridViewModel.Factory,
    private val textFeedbackContentViewModelFactory: TextFeedbackContentViewModel.Factory,
    private val tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
    private val inFirstPageViewModel: InFirstPageViewModel,
) : PaginatableGridLayout {

    @Composable
    override fun ContentScope.TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        listening: () -> Boolean,
        enableRevealEffect: Boolean,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid") {
                viewModelFactory.create()
            }

        val context = LocalContext.current
        val textFeedbackViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.TileGrid", key = context) {
                textFeedbackContentViewModelFactory.create(context)
            }

        val columns = viewModel.columnsWithMediaViewModel.columns
        val largeTilesSpan = viewModel.columnsWithMediaViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState
        // Tiles or largeTiles may be updated while this is composed, so listen to any changes
        val sizedTiles =
            remember(tiles, largeTiles, largeTilesSpan) {
                tiles.map {
                    SizedTileImpl(it, if (largeTiles.contains(it.spec)) largeTilesSpan else 1)
                }
            }
        val squishiness by viewModel.squishinessViewModel.squishiness.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        // Pills, player, then the little controls again.
        val petalMediaRow = LocalPetalMediaRow.current
        val petalSkin = PetalQsSkin.enabled()
        if (petalSkin && petalMediaRow != null) {
            // First two get pills; leave the user's other sizes alone.
            val connectivitySpan = (columns / 2).coerceAtLeast(1)
            val petalLarge =
                sizedTiles.take(2).map { SizedTileImpl(it.tile, connectivitySpan) }
            val petalCircles =
                sizedTiles.drop(2).map {
                    // Resized favourites still get their half row.
                    SizedTileImpl(it.tile, if (it.width > 1) connectivitySpan else 1)
                }
            // Two rows per page; side swipes leave the shade gesture alone.
            val petalCirclePages =
                remember(petalCircles, columns) {
                    splitInRows(petalCircles, columns)
                        .chunked(PetalQsSkin.petalQsPageRows)
                        .map { page ->
                            page.map { row -> row.map { SizedTileImpl(it.tile, it.width) } }
                        }
                }
            val petalPagerState = rememberPagerState(0) { petalCirclePages.size }
            val petalTileCount =
                2 + (petalCirclePages.firstOrNull()?.sumOf { it.size } ?: 0)
            // Rewind while shut so nobody sees the bloody snap.
            LaunchedEffect(listening, petalPagerState) {
                snapshotFlow { listening() }.collect { isListening ->
                    if (!isListening && petalPagerState.pageCount > 0) {
                        petalPagerState.scrollToPage(0)
                    }
                }
            }
            // Tell stock expansion which page we're on; it was stuck on first.
            LaunchedEffect(petalPagerState) {
                snapshotFlow { petalPagerState.currentPage == 0 }.collect {
                    inFirstPageViewModel.inFirstPage = it
                }
            }
            // fixed height so a short last page doesn't drag the pager and dots with it
            val petalPageHeight =
                PetalQsSkin.compactTileHeight() * PetalQsSkin.petalQsPageRows +
                    dimensionResource(R.dimen.qs_tile_margin_vertical) *
                        (PetalQsSkin.petalQsPageRows - 1)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = modifier,
            ) {
            if (petalLarge.isNotEmpty()) {
                ButtonGroupGrid(
                    sizedTiles = petalLarge,
                    columns = columns,
                    keys = { it.spec },
                    elementKey = { it.spec.toElementKey() },
                    horizontalPadding = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                    modifier = Modifier,
                ) { sizedTile, interactionSource ->
                    val index = petalLarge.indexOfFirst { it.tile.spec == sizedTile.tile.spec }
                    val reveal = rememberPetalQsReveal(index = index, count = petalTileCount)
                    Tile(
                        tile = sizedTile.tile,
                        iconOnly = false,
                        squishiness = { squishiness },
                        tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                        coroutineScope = scope,
                        detailsViewModel = detailsViewModel,
                        isVisible = listening,
                        requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                        enableRevealEffect = enableRevealEffect,
                        bounceableInfo = null,
                        interactionSource = interactionSource,
                        petalReveal = reveal,
                        modifier =
                            Modifier.petalQsTileEntrance(
                                petalLarge.indexOfFirst { it.tile.spec == sizedTile.tile.spec },
                                phase = 1,
                                side =
                                    if (
                                        petalLarge.indexOfFirst {
                                            it.tile.spec == sizedTile.tile.spec
                                        } == 0
                                    ) {
                                        -1f
                                    } else {
                                        1f
                                    },
                                count = petalTileCount,
                                reveal = reveal,
                            ),
                    )
                }
            }
            petalMediaRow()
            if (petalCirclePages.size > 1) {
                HorizontalPager(
                    state = petalPagerState,
                    modifier = Modifier.sysuiResTag("qs_pager").height(petalPageHeight),
                    verticalAlignment = Alignment.Top,
                ) { page ->
                    PetalCirclePage(
                        circlePage = petalCirclePages[page],
                        columns = columns,
                        squishiness = squishiness,
                        listening = listening,
                        enableRevealEffect = enableRevealEffect,
                        textFeedbackViewModel = textFeedbackViewModel,
                        detailsViewModel = detailsViewModel,
                        tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                        scope = scope,
                        // tiles fall in/out as the page slides under the finger
                        pageProgress = {
                            val pos =
                                petalPagerState.currentPage +
                                    petalPagerState.currentPageOffsetFraction
                            (1f - kotlin.math.abs(page - pos)).coerceIn(0f, 1f)
                        },
                        // + when the page sits to the right of centre, so tiles trail the swipe
                        pageSlide = {
                            val pos =
                                petalPagerState.currentPage +
                                    petalPagerState.currentPageOffsetFraction
                            (page - pos).coerceIn(-1f, 1f)
                        },
                        count = 2 + petalCirclePages[page].sumOf { it.size },
                    )
                }
            } else {
                // Tiles might still be loading, don't crash the damn shade.
                if (petalCirclePages.isNotEmpty()) {
                    PetalCirclePage(
                        circlePage = petalCirclePages.first(),
                        columns = columns,
                        squishiness = squishiness,
                        listening = listening,
                        enableRevealEffect = enableRevealEffect,
                        textFeedbackViewModel = textFeedbackViewModel,
                        detailsViewModel = detailsViewModel,
                        tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                        scope = scope,
                        count = 2 + petalCirclePages.first().sumOf { it.size },
                    )
                }
            }
            if (petalCirclePages.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                val dotsIndex = 2 + columns * PetalQsSkin.petalQsPageRows
                Box(
                    modifier =
                        Modifier.align(Alignment.CenterHorizontally)
                            .petalQsTileEntrance(dotsIndex, phase = 1, count = petalTileCount)
                ) {
                    PagerDots(
                        pagerState = petalPagerState,
                        activeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        nonActiveColor =
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
            }
            TileListener(tiles, listening)
            return
        }

        if (QSMaterialExpressiveTiles.isEnabled) {
            ButtonGroupGrid(
                sizedTiles = sizedTiles,
                columns = columns,
                keys = { it.spec },
                elementKey = { it.spec.toElementKey() },
                horizontalPadding = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                modifier = modifier,
            ) { sizedTile, interactionSource ->
                Tile(
                    tile = sizedTile.tile,
                    iconOnly = iconTilesViewModel.isIconTile(sizedTile.tile.spec),
                    squishiness = { squishiness },
                    tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                    coroutineScope = scope,
                    detailsViewModel = detailsViewModel,
                    isVisible = listening,
                    requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                    enableRevealEffect = enableRevealEffect,
                    bounceableInfo = null,
                    interactionSource = interactionSource,
                )
            }
        } else {
            val bounceables =
                remember(sizedTiles) { List(sizedTiles.size) { BounceableTileViewModel() } }
            val spans by remember(sizedTiles) { derivedStateOf { sizedTiles.fastMap { it.width } } }
            VerticalSpannedGrid(
                columns = columns,
                columnSpacing = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                rowSpacing = dimensionResource(R.dimen.qs_tile_margin_vertical),
                spans = spans,
                keys = { sizedTiles[it].tile.spec },
                modifier = modifier,
            ) { spanIndex, column, isFirstInColumn, isLastInColumn ->
                val it = sizedTiles[spanIndex]

                Element(it.tile.spec.toElementKey(), Modifier) {
                    Tile(
                        tile = it.tile,
                        iconOnly = iconTilesViewModel.isIconTile(it.tile.spec),
                        squishiness = { squishiness },
                        tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                        coroutineScope = scope,
                        bounceableInfo =
                            bounceables.bounceableInfo(
                                it,
                                index = spanIndex,
                                column = column,
                                columns = columns,
                                isFirstInRow = isFirstInColumn,
                                isLastInRow = isLastInColumn,
                            ),
                        detailsViewModel = detailsViewModel,
                        isVisible = listening,
                        requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                        enableRevealEffect = enableRevealEffect,
                        interactionSource = null,
                    )
                }
            }
        }

        TileListener(tiles, listening)
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
        onSetTiles: (List<TileSpec>) -> Unit,
        onStopEditing: () -> Unit,
    ) {
        val viewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModelFactory.create()
            }
        val columnsViewModel =
            rememberViewModel(traceName = "InfiniteGridLayout.EditTileGrid") {
                viewModel.columnsWithMediaViewModelFactory.createWithoutMediaTracking()
            }
        val snapshotViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.snapshotViewModelFactory.create()
            }
        val topBarActionsViewModel =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.editTopBarActionsViewModelFactory.create()
            }
        val scrollState = rememberScrollState()
        val coroutineScope = rememberCoroutineScope()
        val dialogDelegate =
            rememberViewModel("InfiniteGridLayout.EditTileGrid") {
                viewModel.resetDialogDelegateFactory.create {
                    // Clear the stack of snapshots on reset
                    snapshotViewModel.clearStack()

                    // Automatically scroll to the top on reset
                    coroutineScope.launch { scrollState.animateScrollTo(0) }
                }
            }
        val actions =
            remember(topBarActionsViewModel) { topBarActionsViewModel.actions.toMutableStateList() }
        val columns = columnsViewModel.columns
        val largeTilesSpan = columnsViewModel.largeSpan
        val largeTiles by viewModel.iconTilesViewModel.largeTilesState

        val currentTiles by rememberUpdatedState(tiles.filter { it.isCurrent })
        val listState =
            remember(columns, largeTilesSpan) {
                EditTileListState(
                    currentTiles,
                    largeTiles,
                    columns = columns,
                    largeTilesSpan = largeTilesSpan,
                )
            }
        LaunchedEffect(currentTiles, largeTiles) { listState.updateTiles(currentTiles, largeTiles) }

        DefaultEditTileGrid(
            listState = listState,
            allTiles = tiles,
            modifier = modifier,
            scrollState = scrollState,
            snapshotViewModel = snapshotViewModel,
            onStopEditing = onStopEditing,
            topBarActions = actions,
        ) { action ->
            // Opening the dialog doesn't require a snapshot
            if (action != EditAction.ResetGrid) {
                snapshotViewModel.takeSnapshot(currentTiles.map { it.tileSpec }, largeTiles)
            }

            when (action) {
                is EditAction.AddTile -> {
                    onAddTile(action.tileSpec, listState.tileSpecs().size)
                }
                is EditAction.InsertTile -> {
                    onAddTile(action.tileSpec, action.position)
                }
                is EditAction.RemoveTile -> {
                    onRemoveTile(action.tileSpec)
                }
                EditAction.ResetGrid -> {
                    dialogDelegate.showDialog()
                }
                is EditAction.ResizeTile -> {
                    iconTilesViewModel.resize(action.tileSpec, action.toIcon)
                }
                is EditAction.SetTiles -> {
                    onSetTiles(action.tileSpecs)
                }
            }
        }
    }
}

// One page of circles, keep the pager rows together.
@Composable
private fun ContentScope.PetalCirclePage(
    circlePage: List<List<SizedTileImpl<TileViewModel>>>,
    columns: Int,
    squishiness: Float,
    listening: () -> Boolean,
    enableRevealEffect: Boolean,
    textFeedbackViewModel: TextFeedbackContentViewModel,
    detailsViewModel: DetailsViewModel,
    tileHapticsViewModelFactoryProvider: TileHapticsViewModelFactoryProvider,
    scope: CoroutineScope,
    pageProgress: (() -> Float)? = null,
    pageSlide: (() -> Float)? = null,
    count: Int = 0,
) {
    // rows stack inside a Column so the pager height stays honest
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical))) {
        for ((rowIndex, row) in circlePage.withIndex()) {
            ButtonGroupGrid(
                sizedTiles = row,
                columns = columns,
                keys = { it.spec },
                elementKey = { it.spec.toElementKey() },
                horizontalPadding = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                modifier = Modifier,
            ) { sizedTile, interactionSource ->
                // Count the actual tiles; resized rows threw this crap off.
                val col = row.indexOfFirst { it.tile.spec == sizedTile.tile.spec }.coerceAtLeast(0)
                val half = (row.size - 1) / 2f
                val side = if (half <= 0f) 0f else (col - half) / half
                val index = 2 + circlePage.take(rowIndex).sumOf { it.size } + col
                val reveal = rememberPetalQsReveal(index = index, count = count, pageProgress = pageProgress)
                Tile(
                    tile = sizedTile.tile,
                    iconOnly = sizedTile.width == 1,
                    squishiness = { squishiness },
                    tileHapticsViewModelFactoryProvider = tileHapticsViewModelFactoryProvider,
                    coroutineScope = scope,
                    detailsViewModel = detailsViewModel,
                    isVisible = listening,
                    requestToggleTextFeedback = textFeedbackViewModel::requestShowFeedback,
                    enableRevealEffect = enableRevealEffect,
                    bounceableInfo = null,
                    interactionSource = interactionSource,
                    petalReveal = reveal,
                    modifier =
                        Modifier.petalQsTileEntrance(
                            index,
                            phase = 1,
                            pageProgress = pageProgress,
                            pageSlide = pageSlide,
                            side = side,
                            count = count,
                            reveal = reveal,
                        ),
                )
            }
        }
    }
}
