package com.ghostchu.quickshop.addon.exchange.ui;

import com.ghostchu.quickshop.addon.exchange.repository.AccountAssetBalance;
import com.ghostchu.quickshop.addon.exchange.transfer.model.TransferRecord;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Combined asynchronous data required to render the asset page. */
record AssetPageSnapshot(List<AccountAssetBalance> assets, List<TransferRecord> transfers,
                         Throwable failure) {
  AssetPageSnapshot {
    assets = List.copyOf(assets);
    transfers = List.copyOf(transfers);
  }

  static CompletableFuture<AssetPageSnapshot> combine(
      CompletableFuture<List<AccountAssetBalance>> assets,
      CompletableFuture<List<TransferRecord>> transfers) {
    return result(assets).thenCombine(result(transfers), (assetResult, transferResult) ->
        new AssetPageSnapshot(assetResult.value(), transferResult.value(),
            assetResult.failure() != null ? assetResult.failure() : transferResult.failure()));
  }

  private static <T> CompletableFuture<Result<List<T>>> result(CompletableFuture<List<T>> future) {
    return future.handle((value, failure) -> new Result<>(
        value == null ? List.of() : List.copyOf(value), unwrap(failure)));
  }

  private static Throwable unwrap(Throwable failure) {
    if (failure instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return failure;
  }

  private record Result<T>(T value, Throwable failure) {}
}
