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
import com.android.systemui.qs.panels.ui.viewmodel.InfiniteGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TextFeedbackContentViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.shared.ui.QuickSettings.Elements.toElementKey
import com.android.systemui.petalos.LocalPetalMediaRow
import com.android.systemui.petalos.PetalQsSkin
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

        // petalOS: control-center layout — the large tiles (Wi-Fi / Bluetooth pills) come first,
        // then the concept's middle row (media player + brightness & volume vertical pills)
        // provided through LocalPetalMediaRow, then the circular icon-only tiles.
        val petalMediaRow = LocalPetalMediaRow.current
        val petalSkin = PetalQsSkin.isEnabled(context)
        if (petalSkin && petalMediaRow != null) {
            // The artwork reserves the first two slots for half-width pills. After those slots,
            // retain the user's expanded/compact resize choices using petal pill/circle geometry.
            val connectivitySpan = (columns / 2).coerceAtLeast(1)
            val petalLarge =
                sizedTiles.take(2).map { SizedTileImpl(it.tile, connectivitySpan) }
            val petalCircles =
                sizedTiles.drop(2).map {
                    // Preserve the user's resize choice after the two concept headline slots.
                    // Expanded tiles use the same half-row pill span; compact tiles stay circular.
                    SizedTileImpl(it.tile, if (it.width > 1) connectivitySpan else 1)
                }
            // petalOS: at most two rows of normal tiles per page (PetalQsSkin.petalQsPageRows).
            // Pages beyond the first are reachable through a horizontal pager so the shade keeps
            // opening and closing in a single vertical swipe. The petal layout composes its
            // button groups and pager directly and does not depend on the AOSP
            // qs_material_expressive_tiles flag, which is disabled in this build.
            val petalCirclePages =
                remember(petalCircles, columns) {
                    splitInRows(petalCircles, columns)
                        .chunked(PetalQsSkin.petalQsPageRows)
                        .map { page ->
                            page.map { row -> row.map { SizedTileImpl(it.tile, it.width) } }
                        }
                }
            val petalPagerState = rememberPagerState(0) { petalCirclePages.size }
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
                    )
                }
                petalMediaRow()
            }
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
                    )
                }
            } else {
                // petalOS: on a cold boot the tile list can still be empty while the petal skin is
                // active, which makes petalCirclePages an empty list. Guard the non-pager branch so
                // we don't crash SystemUI with NoSuchElementException while tiles are still loading.
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
                    )
                }
            }
            if (petalCirclePages.size > 1) {
                Spacer(modifier = Modifier.height(12.dp))
                PagerDots(
                    pagerState = petalPagerState,
                    activeColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    nonActiveColor =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
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

/**
 * petalOS: one page of circle tiles (at most [PetalQsSkin.petalQsPageRows] rows), rendered with the
 * expressive [ButtonGroupGrid]. Used by the petal pager in [TileGrid].
 */
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
) {
    // this loop wasn't in a Column, rows stacked on top of each other, how gay
    Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.qs_tile_margin_vertical))) {
        for (row in circlePage) {
            ButtonGroupGrid(
                sizedTiles = row,
                columns = columns,
                keys = { it.spec },
                elementKey = { it.spec.toElementKey() },
                horizontalPadding = dimensionResource(R.dimen.qs_tile_margin_horizontal),
                modifier = Modifier,
            ) { sizedTile, interactionSource ->
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
                )
            }
        }
    }
}