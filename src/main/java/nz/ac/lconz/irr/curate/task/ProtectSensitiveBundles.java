package nz.ac.lconz.irr.curate.task;

import org.dspace.authorize.AuthorizeManager;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.content.Item;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.content.DSpaceObject;
import org.dspace.core.Context;
import org.dspace.curate.AbstractCurationTask;
import org.dspace.curate.Curator;
import org.dspace.curate.Mutative;
import org.dspace.eperson.Group;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Curation task that protects non-public bundles. All authorisation policies are removed for all
 * non-public bundles and bitstreams in these bundles.
 *
 * By default, the only public bundles are ORIGINAL, LICENSE, CC-LICENSE, THUMBNAIL and TEXT.
 * An alternative set of public bundles can be specified via the lconz-curation config file,
 * property bundleprotect.public.bundles.
 *
 * @author Andrea Schweer schweer@waikato.ac.nz for LCoNZ IRR project
 */
@Mutative
public class ProtectSensitiveBundles extends AbstractCurationTask {

	private List<String> publicBundles;

	@Override
	public void init(Curator curator, String taskId) throws IOException {
		super.init(curator, taskId);
		String publicBundlesProperty = ConfigurationManager.getProperty("lconz-curation", "bundleprotect.public.bundles");
		if (publicBundlesProperty != null && !publicBundlesProperty.trim().equals("")) {
			String[] publicBundleNames = publicBundlesProperty.split("\\,\\s*");
			publicBundles = Arrays.asList(publicBundleNames);
		}
		if (publicBundles == null) {
			publicBundles = Arrays.asList(new String[] {"ORIGINAL", "LICENSE", "CC-LICENSE", "THUMBNAIL", "TEXT"});
		}
	}

	@Override
	public int perform(DSpaceObject dso) throws IOException {
		boolean changes = false;
		if (dso.getType() == Constants.ITEM || dso.getType() == Constants.BUNDLE) {
			Context context = null;
			try {
				context = new Context();
				context.ignoreAuthorization();
				changes = process(dso, context);
				if (changes) {
					context.complete();
					context = null;
				} else {
					context.abort();
					context = null;
				}
			} catch (SQLException e) {
				report(e.getMessage());
				return Curator.CURATE_ERROR;
			} finally {
				if (context != null) {
					context.abort();
				}
			}
		} else {
			return Curator.CURATE_SKIP;
		}
		
		return changes ? Curator.CURATE_SUCCESS : Curator.CURATE_FAIL;
	}

	private boolean process(DSpaceObject dso, Context context) throws SQLException {
		if (dso.getType() == Constants.ITEM) {
			return processItem((Item) dso, context);
		} else if (dso.getType() == Constants.BUNDLE) {
			return processBundle((Bundle) dso, context);
		}
		return false;
	}

	private boolean processItem(Item item, Context context) throws SQLException {
		if (!item.isArchived()) {
			return false; // don't mess with workflows
		}

		Bundle[] bundles = item.getBundles();
		boolean changes = false;
		for (Bundle bundle : bundles) {
			changes |= process(bundle, context);
		}
		return changes;
	}

	private boolean processBundle(Bundle bundle, Context context) throws SQLException {
		boolean changes = false;
		if (isProtectedBundle(bundle.getName())) {
			Group[] bundleReadGroups = AuthorizeManager.getAuthorizedGroups(context, bundle, Constants.READ);
			if (bundleReadGroups != null && bundleReadGroups.length > 0) {
				AuthorizeManager.removePoliciesActionFilter(context, bundle, Constants.READ);
				changes = true;
			}
			for (Bitstream bitstream : bundle.getBitstreams()) {
				Group[] bitstreamReadGroups = AuthorizeManager.getAuthorizedGroups(context, bitstream, Constants.READ);
				if (bitstreamReadGroups != null && bitstreamReadGroups.length > 0) {
					AuthorizeManager.removePoliciesActionFilter(context, bitstream, Constants.READ);
					changes = true;
				}
			}
		}
		return changes;
	}

	private boolean isProtectedBundle(String bundleName) {
		return !publicBundles.contains(bundleName);
	}
}