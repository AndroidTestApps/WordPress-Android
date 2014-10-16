package org.wordpress.android.ui.reader.actions;

import android.os.Handler;
import android.text.TextUtils;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONObject;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderLikeTable;
import org.wordpress.android.datasets.ReaderPostTable;
import org.wordpress.android.datasets.ReaderTagTable;
import org.wordpress.android.datasets.ReaderUserTable;
import org.wordpress.android.models.ReaderPost;
import org.wordpress.android.models.ReaderPostList;
import org.wordpress.android.models.ReaderTag;
import org.wordpress.android.models.ReaderTagType;
import org.wordpress.android.models.ReaderUserIdList;
import org.wordpress.android.models.ReaderUserList;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.actions.ReaderActions.ActionListener;
import org.wordpress.android.ui.reader.actions.ReaderActions.RequestDataAction;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResult;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResultListener;
import org.wordpress.android.ui.reader.utils.ReaderUtils;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.AppLog.T;
import org.wordpress.android.util.JSONUtil;
import org.wordpress.android.util.UrlUtils;
import org.wordpress.android.util.VolleyUtils;

import java.util.HashMap;
import java.util.Map;

public class ReaderPostActions {

    private ReaderPostActions() {
        throw new AssertionError();
    }

    /**
     * like/unlike the passed post
     */
    public static boolean performLikeAction(final ReaderPost post,
                                            final boolean isAskingToLike) {
        // do nothing if post's like state is same as passed
        boolean isCurrentlyLiked = ReaderPostTable.isPostLikedByCurrentUser(post);
        if (isCurrentlyLiked == isAskingToLike) {
            AppLog.w(T.READER, "post like unchanged");
            return false;
        }

        // update like status and like count in local db
        int newNumLikes = (isAskingToLike ? post.numLikes + 1 : post.numLikes - 1);
        ReaderPostTable.setLikesForPost(post, newNumLikes, isAskingToLike);
        ReaderLikeTable.setCurrentUserLikesPost(post, isAskingToLike);

        final String actionName = isAskingToLike ? "like" : "unlike";
        String path = "sites/" + post.blogId + "/posts/" + post.postId + "/likes/";
        if (isAskingToLike) {
            path += "new";
        } else {
            path += "mine/delete";
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                AppLog.d(T.READER, String.format("post %s succeeded", actionName));
            }
        };

        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                String error = VolleyUtils.errStringFromVolleyError(volleyError);
                if (TextUtils.isEmpty(error)) {
                    AppLog.w(T.READER, String.format("post %s failed", actionName));
                } else {
                    AppLog.w(T.READER, String.format("post %s failed (%s)", actionName, error));
                }
                AppLog.e(T.READER, volleyError);
                ReaderPostTable.setLikesForPost(post, post.numLikes, post.isLikedByCurrentUser);
                ReaderLikeTable.setCurrentUserLikesPost(post, post.isLikedByCurrentUser);
            }
        };

        WordPress.getRestClientUtils().post(path, listener, errorListener);
        return true;
    }

    /*
     * reblogs the passed post to the passed destination with optional comment
     * https://developer.wordpress.com/docs/api/1/post/sites/%24site/posts/%24post_ID/reblogs/new/
     */
    public static void reblogPost(final ReaderPost post,
                                  long destinationBlogId,
                                  final String optionalComment,
                                  final ActionListener actionListener) {
        if (post == null) {
            if (actionListener != null) {
                actionListener.onActionResult(false);
            }
            return;
        }

        Map<String, String> params = new HashMap<String, String>();
        params.put("destination_site_id", Long.toString(destinationBlogId));
        if (!TextUtils.isEmpty(optionalComment)) {
            params.put("note", optionalComment);
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                boolean isReblogged = (jsonObject != null && JSONUtil.getBool(jsonObject, "is_reblogged"));
                if (isReblogged) {
                    ReaderPostTable.setPostReblogged(post, true);
                }
                if (actionListener != null) {
                    actionListener.onActionResult(isReblogged);
                }
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (actionListener != null) {
                    actionListener.onActionResult(false);
                }

            }
        };

        String path = "/sites/" + post.blogId
                    + "/posts/" + post.postId
                    + "/reblogs/new";
        WordPress.getRestClientUtils().post(path, params, null, listener, errorListener);
    }

    /*
     * get the latest version of this post - note that the post is only considered changed if the
     * like/comment count has changed, or if the current user's like/follow status has changed
     */
    public static void updatePost(final ReaderPost originalPost,
                                  final UpdateResultListener resultListener) {
        String path = "sites/" + originalPost.blogId + "/posts/" + originalPost.postId + "/?meta=site,likes";

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdatePostResponse(originalPost, jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (resultListener != null) {
                    resultListener.onUpdateResult(UpdateResult.FAILED);
                }
            }
        };
        AppLog.d(T.READER, "updating post");
        WordPress.getRestClientUtils().get(path, null, null, listener, errorListener);
    }

    private static void handleUpdatePostResponse(final ReaderPost originalPost,
                                                 final JSONObject jsonObject,
                                                 final UpdateResultListener resultListener) {
        if (jsonObject == null) {
            if (resultListener != null) {
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
            return;
        }

        final Handler handler = new Handler();

        new Thread() {
            @Override
            public void run() {
                ReaderPost updatedPost = ReaderPost.fromJson(jsonObject);
                boolean hasChanges =
                         ( updatedPost.numReplies != originalPost.numReplies
                        || updatedPost.numLikes != originalPost.numLikes
                        || updatedPost.isCommentsOpen != originalPost.isCommentsOpen
                        || updatedPost.isLikedByCurrentUser != originalPost.isLikedByCurrentUser
                        || updatedPost.isFollowedByCurrentUser != originalPost.isFollowedByCurrentUser);

                if (hasChanges) {
                    AppLog.d(T.READER, "post updated");
                    // set the featured image for the updated post to that of the original
                    // post - this should be done even if the updated post has a featured
                    // image since that may have been set by ReaderPost.findFeaturedImage()
                    if (originalPost.hasFeaturedImage()) {
                        updatedPost.setFeaturedImage(originalPost.getFeaturedImage());
                    }

                    // likewise for featured video
                    if (originalPost.hasFeaturedVideo()) {
                        updatedPost.setFeaturedVideo(originalPost.getFeaturedVideo());
                        updatedPost.isVideoPress = originalPost.isVideoPress;
                    }

                    // retain the pubDate and timestamp of the original post - this is important
                    // since these control how the post is sorted in the list view, and we don't
                    // want that sorting to change
                    updatedPost.timestamp = originalPost.timestamp;
                    updatedPost.setPublished(originalPost.getPublished());

                    ReaderPostTable.addOrUpdatePost(updatedPost);
                }

                // always update liking users regardless of whether changes were detected - this
                // ensures that the liking avatars are immediately available to post detail
                if (handlePostLikes(updatedPost, jsonObject)) {
                    hasChanges = true;
                }

                if (resultListener != null) {
                    final UpdateResult result = (hasChanges ? UpdateResult.CHANGED : UpdateResult.UNCHANGED);
                    handler.post(new Runnable() {
                        public void run() {
                            resultListener.onUpdateResult(result);
                        }
                    });
                }
            }
        }.start();
    }

    /*
     * updates local liking users based on the "likes" meta section of the post's json - requires
     * using the /sites/ endpoint with ?meta=likes - returns true if likes have changed
     */
    private static boolean handlePostLikes(final ReaderPost post, JSONObject jsonPost) {
        if (post == null || jsonPost == null) {
            return false;
        }

        JSONObject jsonLikes = JSONUtil.getJSONChild(jsonPost, "meta/data/likes");
        if (jsonLikes == null) {
            return false;
        }

        ReaderUserList likingUsers = ReaderUserList.fromJsonLikes(jsonLikes);
        ReaderUserIdList likingUserIds = likingUsers.getUserIds();

        ReaderUserIdList existingIds = ReaderLikeTable.getLikesForPost(post);
        if (likingUserIds.isSameList(existingIds)) {
            return false;
        }

        ReaderUserTable.addOrUpdateUsers(likingUsers);
        ReaderLikeTable.setLikesForPost(post, likingUserIds);
        return true;
    }

    /**
     * similar to updatePost, but used when post doesn't already exist in local db
     **/
    public static void requestPost(final long blogId, final long postId, final ActionListener actionListener) {
        String path = "sites/" + blogId + "/posts/" + postId + "/?meta=site,likes";

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                ReaderPost post = ReaderPost.fromJson(jsonObject);
                // make sure the post has the passed blogId so it's saved correctly - necessary
                // since the /sites/ endpoints return site_id="1" for Jetpack-powered blogs
                post.blogId = blogId;
                ReaderPostTable.addOrUpdatePost(post);
                handlePostLikes(post, jsonObject);
                if (actionListener != null) {
                    actionListener.onActionResult(true);
                }
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (actionListener != null) {
                    actionListener.onActionResult(false);
                }
            }
        };
        AppLog.d(T.READER, "requesting post");
        WordPress.getRestClientUtils().get(path, null, null, listener, errorListener);
    }

    /*
     * get the latest posts in the passed topic - note that this uses an UpdateResultAndCountListener
     * so the caller can be told how many new posts were added
     */
    public static void updatePostsInTag(final ReaderTag tag,
                                        final RequestDataAction updateAction,
                                        final UpdateResultListener resultListener) {
        String endpoint = getEndpointForTag(tag);
        if (TextUtils.isEmpty(endpoint)) {
            if (resultListener != null) {
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
            return;
        }

        StringBuilder sb = new StringBuilder(endpoint);

        // append #posts to retrieve
        sb.append("?number=").append(ReaderConstants.READER_MAX_POSTS_TO_REQUEST);

        // return newest posts first (this is the default, but make it explicit since it's important)
        sb.append("&order=DESC");

        // apply the after/before to limit results based on previous update
        switch (updateAction) {
            case LOAD_NEWER:
                String dateNewest = ReaderTagTable.getTagLastUpdated(tag);
                if (!TextUtils.isEmpty(dateNewest)) {
                    sb.append("&after=").append(UrlUtils.urlEncode(dateNewest));
                    AppLog.d(T.READER, String.format("requesting newer posts in tag %s (%s)", tag.getTagNameForLog(), dateNewest));
                }
                break;

            case LOAD_OLDER:
                String dateOldest = ReaderPostTable.getOldestPubDateWithTag(tag);
                if (!TextUtils.isEmpty(dateOldest)) {
                    sb.append("&before=").append(UrlUtils.urlEncode(dateOldest));
                    AppLog.d(T.READER, String.format("requesting older posts in tag %s (%s)", tag.getTagNameForLog(), dateOldest));
                }
                break;
        }

        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdatePostsWithTagResponse(tag, updateAction, jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (resultListener != null) {
                    resultListener.onUpdateResult(UpdateResult.FAILED);
                }
            }
        };

        WordPress.getRestClientUtils().get(sb.toString(), null, null, listener, errorListener);
    }

    private static void handleUpdatePostsWithTagResponse(final ReaderTag tag,
                                                         final RequestDataAction updateAction,
                                                         final JSONObject jsonObject,
                                                         final UpdateResultListener resultListener) {
        if (jsonObject == null) {
            if (resultListener != null) {
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
            return;
        }
        final Handler handler = new Handler();

        new Thread() {
            @Override
            public void run() {
                final ReaderPostList serverPosts = ReaderPostList.fromJson(jsonObject);

                // go no further if the response didn't contain any posts
                if (serverPosts.size() == 0) {
                    AppLog.d(T.READER, "no new posts in tag " + tag.getTagNameForLog());
                    if (resultListener != null) {
                        handler.post(new Runnable() {
                            public void run() {
                                resultListener.onUpdateResult(UpdateResult.UNCHANGED);
                            }
                        });
                    }
                    return;
                }

                // determine if any of the downloaded posts are new or changed
                final UpdateResult updateResult = ReaderPostTable.comparePosts(serverPosts);
                if (updateResult.isNewOrChanged()) {
                    ReaderPostTable.addOrUpdatePosts(tag, serverPosts);
                }

                switch (updateResult) {
                    case HAS_NEW:
                        AppLog.d(T.READER, String.format("retrieved %d posts in tag %s (has new)",
                                serverPosts.size(), tag.getTagNameForLog()));
                        break;
                    case CHANGED:
                        AppLog.d(T.READER, String.format("retrieved %d posts in tag %s (has changes)",
                                serverPosts.size(), tag.getTagNameForLog()));
                        break;
                    default:
                        AppLog.d(T.READER, String.format("retrieved %d posts in tag %s (no new or changed)",
                                serverPosts.size(), tag.getTagNameForLog()));
                        break;
                }

                // remember when this tag was updated if newer posts were requested - note this
                // is done regardless of whether new/changed posts were retrieved since the update
                // date is used when determining whether it's time to auto-update this tag
                if (updateAction == RequestDataAction.LOAD_NEWER) {
                    ReaderTagTable.setTagLastUpdated(tag);
                }

                if (resultListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            resultListener.onUpdateResult(updateResult);
                        }
                    });
                }
            }
        }.start();
    }

    /*
     * get the latest posts in the passed blog
     */
    public static void requestPostsForBlog(final long blogId,
                                           final String blogUrl,
                                           final RequestDataAction updateAction,
                                           final UpdateResultListener resultListener) {
        String path;
        if (blogId == 0) {
            path = "sites/" + UrlUtils.getDomainFromUrl(blogUrl);
        } else {
            path = "sites/" + blogId;
        }
        path += "/posts/?meta=site,likes";

        // append the date of the oldest cached post in this blog when requesting older posts
        if (updateAction == RequestDataAction.LOAD_OLDER) {
            String dateOldest = ReaderPostTable.getOldestPubDateInBlog(blogId);
            if (!TextUtils.isEmpty(dateOldest)) {
                path += "&before=" + UrlUtils.urlEncode(dateOldest);
            }
        }
        com.wordpress.rest.RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleGetPostsForBlogResponse(jsonObject, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(T.READER, volleyError);
                if (resultListener != null) {
                    resultListener.onUpdateResult(UpdateResult.FAILED);
                }
            }
        };
        AppLog.d(T.READER, "updating posts in blog " + blogId);
        WordPress.getRestClientUtils().get(path, null, null, listener, errorListener);
    }

    private static void handleGetPostsForBlogResponse(final JSONObject jsonObject, final UpdateResultListener resultListener) {
        if (jsonObject == null) {
            if (resultListener != null) {
                resultListener.onUpdateResult(UpdateResult.FAILED);
            }
            return;
        }

        final Handler handler = new Handler();
        new Thread() {
            @Override
            public void run() {
                ReaderPostList serverPosts = ReaderPostList.fromJson(jsonObject);
                final UpdateResult updateResult = ReaderPostTable.comparePosts(serverPosts);
                if (updateResult.isNewOrChanged()) {
                    ReaderPostTable.addOrUpdatePosts(null, serverPosts);
                }
                if (resultListener != null) {
                    handler.post(new Runnable() {
                        public void run() {
                            resultListener.onUpdateResult(updateResult);
                        }
                    });
                }
            }
        }.start();
    }

    /*
     * returns the endpoint to use for the passed tag - first gets it from local db, if not
     * there it generates it "by hand"
     */
    private static String getEndpointForTag(ReaderTag tag) {
        if (tag == null) {
            return null;
        }

        // if passed tag has an assigned endpoint, return it and be done
        if (!TextUtils.isEmpty(tag.getEndpoint())) {
            return tag.getEndpoint();
        }

        // check the db for the endpoint
        String endpoint = ReaderTagTable.getEndpointForTag(tag);
        if (!TextUtils.isEmpty(endpoint)) {
            return endpoint;
        }

        // never hand craft the endpoint for default tags, since these MUST be updated
        // using their stored endpoints
        if (tag.tagType == ReaderTagType.DEFAULT) {
            return null;
        }

        return String.format("/read/tags/%s/posts", ReaderUtils.sanitizeTagName(tag.getTagName()));
    }

}
