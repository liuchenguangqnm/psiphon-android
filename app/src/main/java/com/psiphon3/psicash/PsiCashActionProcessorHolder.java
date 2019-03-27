package com.psiphon3.psicash;

import android.content.Context;
import android.util.Log;

import java.util.Arrays;

import io.reactivex.Observable;
import io.reactivex.ObservableTransformer;

class PsiCashActionProcessorHolder {
    private static final String TAG = "PsiCashActionProcessor";
    private final PsiCashListener psiCashListener;
    private final ObservableTransformer<PsiCashAction.ClearErrorState, PsiCashResult.ClearErrorState> clearErrorStateProcessor;
    private final ObservableTransformer<PsiCashAction.GetPsiCashRemote, PsiCashResult> getPsiCashRemoteProcessor;
    private final ObservableTransformer<PsiCashAction.GetPsiCashLocal, PsiCashResult> getPsiCashLocalProcessor;
    private final ObservableTransformer<PsiCashAction.MakeExpiringPurchase, PsiCashResult> makeExpiringPurchaseProcessor;
    private final ObservableTransformer<PsiCashAction.RemovePurchases, PsiCashResult> removePurchasesProcessor;
    private final ObservableTransformer<PsiCashAction.LoadVideoAd, PsiCashResult> loadVideoAdProcessor;

    final ObservableTransformer<PsiCashAction, PsiCashResult> actionProcessor;

    public PsiCashActionProcessorHolder(Context context, PsiCashListener listener) {
        this.psiCashListener = listener;

        this.clearErrorStateProcessor = actions -> actions.map(a -> PsiCashResult.ClearErrorState.success());

        this.getPsiCashRemoteProcessor = actions ->
                actions.flatMap(action -> PsiCashClient.getInstance(context).getPsiCashRemote(action.connectionState())
                                .map(PsiCashResult.GetPsiCash::success)
                                .onErrorReturn(PsiCashResult.GetPsiCash::failure)
                                .startWith(PsiCashResult.GetPsiCash.inFlight()));

        this.getPsiCashLocalProcessor = actions ->
                actions.flatMap(action -> PsiCashClient.getInstance(context).getPsiCashLocal()
                        .map(PsiCashResult.GetPsiCash::success)
                        .onErrorReturn(PsiCashResult.GetPsiCash::failure)
                        .startWith(PsiCashResult.GetPsiCash.inFlight()));

        this.makeExpiringPurchaseProcessor = actions ->
                actions.flatMap(action ->
                        PsiCashClient.getInstance(context).makeExpiringPurchase(action.connectionState(), action.purchasePrice(), action.hasActiveBoost())
                                .map(r -> {
                                    if (r instanceof PsiCashModel.ExpiringPurchase) {
                                        if (psiCashListener != null) {
                                            psiCashListener.onNewExpiringPurchase(context, ((PsiCashModel.ExpiringPurchase) r).expiringPurchase());
                                        }
                                        return PsiCashResult.ExpiringPurchase.success((PsiCashModel.ExpiringPurchase) r);
                                    } else if (r instanceof PsiCashModel.PsiCash) {
                                        return PsiCashResult.GetPsiCash.success((PsiCashModel.PsiCash) r);
                                    }
                                    throw new IllegalArgumentException("Unknown result: " + r);
                                })
                                .onErrorReturn(PsiCashResult.ExpiringPurchase::failure)
                                .startWith(PsiCashResult.ExpiringPurchase.inFlight()));

        this.removePurchasesProcessor = actions ->
                actions.flatMap(action -> PsiCashClient.getInstance(context).removePurchases(action.purchases())
                        .map(PsiCashResult.GetPsiCash::success)
                        .onErrorReturn(PsiCashResult.GetPsiCash::failure)
                        .startWith(PsiCashResult.GetPsiCash.inFlight()));

        this.loadVideoAdProcessor = actions ->
                actions
                        .doOnEach( s -> Log.d(TAG, "PsiCashActionProcessorHolder: action each" + s))
                        .switchMap(action ->
                        RewardedVideoClient.getInstance().loadRewardedVideo(context, action.connectionState(), PsiCashClient.getInstance(context).rewardedVideoCustomData())
                                .map(r -> {
                                    if (r instanceof PsiCashModel.RewardedVideoState) {
                                        PsiCashModel.RewardedVideoState result = (PsiCashModel.RewardedVideoState) r;
                                        PsiCashModel.RewardedVideoState.VideoState videoState = result.videoState();

                                        if(videoState == PsiCashModel.RewardedVideoState.VideoState.LOADED) {
                                            return PsiCashResult.Video.loaded();
                                        } else if(videoState == PsiCashModel.RewardedVideoState.VideoState.PLAYING){
                                            return PsiCashResult.Video.playing();
                                        }
                                        throw new IllegalArgumentException("Unknown result: " + r);
                                    } else if (r instanceof PsiCashModel.Reward) {
                                        psiCashListener.onNewReward(context, ((PsiCashModel.Reward) r).amount());
                                        return PsiCashResult.Reward.success((PsiCashModel.Reward) r);
                                    }
                                    throw new IllegalArgumentException("Unknown result: " + r);
                                })
                                .startWith(PsiCashResult.Video.loading())
                                .concatWith(Observable.just(PsiCashResult.Video.finished()))
                                .onErrorReturn(PsiCashResult.Video::failure))
                        .doOnEach( s -> Log.d(TAG, "PsiCashActionProcessorHolder: loadRewardedVideo each" + s))
                        .doOnDispose(() -> Log.d(TAG, "PsiCashActionProcessorHolder: loadRewardedVideo disposed"));


        this.actionProcessor = actions ->
                actions.publish(shared -> Observable.merge(
                        Arrays.asList(
                                shared.ofType(PsiCashAction.ClearErrorState.class).compose(clearErrorStateProcessor),
                                shared.ofType(PsiCashAction.MakeExpiringPurchase.class).compose(makeExpiringPurchaseProcessor),
                                shared.ofType(PsiCashAction.GetPsiCashLocal.class).compose(getPsiCashLocalProcessor),
                                shared.ofType(PsiCashAction.RemovePurchases.class).compose(removePurchasesProcessor),
                                shared.ofType(PsiCashAction.GetPsiCashRemote.class).compose(getPsiCashRemoteProcessor),
                                shared.ofType(PsiCashAction.LoadVideoAd.class).compose(loadVideoAdProcessor)
                        )
                )
                        .mergeWith(
                                // Error for not implemented actions
                                shared.filter(v -> !(v instanceof PsiCashAction.ClearErrorState)
                                        && !(v instanceof PsiCashAction.MakeExpiringPurchase)
                                        && !(v instanceof PsiCashAction.GetPsiCashLocal)
                                        && !(v instanceof PsiCashAction.GetPsiCashRemote)
                                        && !(v instanceof PsiCashAction.RemovePurchases)
                                        && !(v instanceof PsiCashAction.LoadVideoAd)
                                )
                                        .flatMap(w -> Observable.error(
                                                new IllegalArgumentException("Unknown action: " + w)))));
    }
}
