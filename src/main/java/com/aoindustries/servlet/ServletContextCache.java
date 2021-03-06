/*
 * ao-servlet-util - Miscellaneous Servlet and JSP utilities.
 * Copyright (C) 2016, 2017, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of ao-servlet-util.
 *
 * ao-servlet-util is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ao-servlet-util is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with ao-servlet-util.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.servlet;

import com.aoindustries.cache.BackgroundCache;
import com.aoindustries.cache.BackgroundCache.Refresher;
import com.aoindustries.cache.BackgroundCache.Result;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.logging.Logger;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * ServletContext methods can be somewhat slow, this offers a cache that refreshes
 * the recently used values in the background.  Importantly, it caches misses as well.
 *
 * @author  AO Industries, Inc.
 */
final public class ServletContextCache {

	private static final Logger logger = Logger.getLogger(ServletContextCache.class.getName());

	private static final long REFRESH_INTERVAL = 5 * 1000;

	private static final long EXPIRATION_AGE = 60 * 1000;

	private static final String APPLICATION_ATTRIBUTE = ServletContextCache.class.getName();

	@WebListener
	public static class Initializer implements ServletContextListener {

		private ServletContextCache cache;

		@Override
		public void contextInitialized(ServletContextEvent event) {
			cache = getInstance(event.getServletContext());
		}

		@Override
		public void contextDestroyed(ServletContextEvent event) {
			event.getServletContext().removeAttribute(APPLICATION_ATTRIBUTE);
			if(cache != null) {
				cache.stop();
				cache = null;
			}
		}
	}

	/**
	 * Gets or creates the cache for the provided servlet context.
	 */
	public static ServletContextCache getInstance(ServletContext servletContext) {
		ServletContextCache cache = (ServletContextCache)servletContext.getAttribute(APPLICATION_ATTRIBUTE);
		if(cache == null) {
			// It is possible this is called during context initialization before the listener
			cache = new ServletContextCache(servletContext);
			servletContext.setAttribute(APPLICATION_ATTRIBUTE, cache);
			//throw new IllegalStateException("ServletContextCache not active in the provided ServletContext.  Add context listener to web.xml?");
		} else {
			assert cache.servletContext == servletContext;
		}
		return cache;
	}

	/**
	 * @deprecated  Please use {@link #getInstance(javax.servlet.ServletContext)}.
	 */
	@Deprecated
	public static ServletContextCache getCache(ServletContext servletContext) {
		return getInstance(servletContext);
	}

	final ServletContext servletContext;

	private ServletContextCache(ServletContext servletContext) {
		this.servletContext = servletContext;
	}

	void stop() {
		getResourceCache.stop();
		getRealPathCache.stop();
	}

	// <editor-fold defaultstate="collapsed" desc="getResource">
	private final BackgroundCache<String, URL, MalformedURLException> getResourceCache = new BackgroundCache<>(
		ServletContextCache.class.getName() + ".getResource",
		MalformedURLException.class,
		REFRESH_INTERVAL,
		EXPIRATION_AGE,
		logger
	);

	private final Refresher<String, URL, MalformedURLException> getResourceRefresher = new Refresher<String, URL, MalformedURLException>() {
		@Override
		public URL call(String path) throws MalformedURLException {
			return servletContext.getResource(path);
		}
	};

	/**
	 * Gets the possibly cached URL.  This URL is not copied and caller should not fiddle with
	 * its state.  Thank you Java for this not being immutable.
	 *
	 * @see  ServletContext#getResource(java.lang.String)
	 */
	public URL getResource(String path) throws MalformedURLException {
		Result<URL, MalformedURLException> result = getResourceCache.get(path, getResourceRefresher);
		MalformedURLException exception = result.getException();
		if(exception != null) throw exception;
		return result.getValue();
	}

	/**
	 * @see  #getResource(java.lang.String)
	 */
	public static URL getResource(ServletContext servletContext, String path) throws MalformedURLException {
		return getInstance(servletContext).getResource(path);
	}
	// </editor-fold>

	// TODO: getRequestDispatcher? (Only if profiling shows it might help)

	// <editor-fold defaultstate="collapsed" desc="getRealPath">
	private final BackgroundCache<String, String, RuntimeException> getRealPathCache = new BackgroundCache<>(
		ServletContextCache.class.getName() + ".getRealPath",
		RuntimeException.class,
		REFRESH_INTERVAL,
		EXPIRATION_AGE,
		logger
	);

	private final Refresher<String, String, RuntimeException> getRealPathRefresher = new Refresher<String, String, RuntimeException>() {
		@Override
		public String call(String path) {
			return servletContext.getRealPath(path);
		}
	};

	/**
	 * @see  ServletContext#getRealPath(java.lang.String)
	 */
	public String getRealPath(String path) {
		Result<String, RuntimeException> result = getRealPathCache.get(path, getRealPathRefresher);
		RuntimeException exception = result.getException();
		if(exception != null) throw exception;
		return result.getValue();
	}

	/**
	 * @see  #getRealPath(java.lang.String)
	 */
	public static String getRealPath(ServletContext servletContext, String path) {
		return getInstance(servletContext).getRealPath(path);
	}
	// </editor-fold>

	// <editor-fold defaultstate="collapsed" desc="getLastModified">
	private final BackgroundCache<String, Long, RuntimeException> getLastModifiedCache = new BackgroundCache<>(
		ServletContextCache.class.getName() + ".getLastModified",
		RuntimeException.class,
		REFRESH_INTERVAL,
		EXPIRATION_AGE,
		logger
	);

	private final Refresher<String, Long, RuntimeException> getLastModifiedRefresher = (String path) -> {
		long lastModified = 0;
		String realPath = getRealPath(path);
		if(realPath != null) {
			// Use File first
			lastModified = new File(realPath).lastModified();
		}
		if(lastModified == 0) {
			// Try URL
			try {
				URL resourceUrl = getResource(path);
				if(resourceUrl != null) {
					URLConnection conn = resourceUrl.openConnection();
					conn.setAllowUserInteraction(false);
					// Are these timeouts appropriate to web-resource URLs?
					// Would they be different for background refresh versus interactive?
					// conn.setConnectTimeout(10);
					// conn.setReadTimeout(10);
					conn.setDoInput(false);
					conn.setDoOutput(false);
					conn.setUseCaches(false);
					lastModified = conn.getLastModified();
				}
			} catch(IOException e) {
				// lastModified stays unmodified
			}
		}
		return lastModified;
	};

	/**
	 * Gets a modified time from either a file or URL.
	 *
	 * @return  The modified time or {@code 0L} when not known
	 *
	 * @see  #getRealPath(java.lang.String)
	 * @see  File#lastModified()
	 * @see  #getResource(java.lang.String)
	 * @see  URLConnection#getLastModified()
	 */
	public long getLastModified(String path) {
		Result<Long, RuntimeException> result = getLastModifiedCache.get(path, getLastModifiedRefresher);
		RuntimeException exception = result.getException();
		if(exception != null) throw exception;
		return result.getValue();
	}

	/**
	 * @see  #getLastModified(java.lang.String)
	 */
	public static long getLastModified(ServletContext servletContext, String path) {
		return getInstance(servletContext).getLastModified(path);
	}
	// </editor-fold>
}
