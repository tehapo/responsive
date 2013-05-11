package com.vaadin.addon.responsive.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gwt.dom.client.Element;
import com.google.gwt.regexp.shared.MatchResult;
import com.google.gwt.regexp.shared.RegExp;
import com.google.gwt.user.client.DOM;
import com.vaadin.addon.responsive.Responsive;
import com.vaadin.client.LayoutManager;
import com.vaadin.client.ServerConnector;
import com.vaadin.client.extensions.AbstractExtensionConnector;
import com.vaadin.client.ui.AbstractComponentConnector;
import com.vaadin.client.ui.layout.ElementResizeEvent;
import com.vaadin.client.ui.layout.ElementResizeListener;
import com.vaadin.shared.ui.Connect;

import elemental.client.Browser;
import elemental.css.CSSImportRule;
import elemental.css.CSSRule;
import elemental.css.CSSRuleList;
import elemental.css.CSSStyleRule;
import elemental.css.CSSStyleSheet;
import elemental.dom.Document;
import elemental.stylesheets.StyleSheetList;

/**
 * The client side connector for the Responsive extension.
 * 
 * @author jouni@vaadin.com
 * @author teemu@vaadin.com
 * 
 */
@Connect(Responsive.class)
public class ResponsiveConnector extends AbstractExtensionConnector implements
        ElementResizeListener {

    private static final long serialVersionUID = -7960943137494057105L;

    /**
     * The target component which we will monitor for width changes
     */
    protected AbstractComponentConnector target;

    /**
     * All the width breakpoints found for this particular instance
     */
    protected List<BreakPoint> widthBreakpoints = new ArrayList<BreakPoint>();

    /**
     * All the height breakpoints found for this particular instance
     */
    protected List<BreakPoint> heightBreakpoints = new ArrayList<BreakPoint>();

    /**
     * All width-range breakpoints found from the style sheets on the page.
     * Common for all instances.
     */
    protected static Set<BreakPoint> widthRangeCache;

    /**
     * All height-range breakpoints found from the style sheets on the page.
     * Common for all instances.
     */
    protected static Set<BreakPoint> heightRangeCache;

    private String currentWidthRanges;
    private String currentHeightRanges;

    @Override
    protected void extend(ServerConnector target) {
        // Initialize cache if not already done
        if (widthRangeCache == null) {
            widthRangeCache = new HashSet<BreakPoint>();
            heightRangeCache = new HashSet<BreakPoint>();
            searchForBreakPoints();
        }

        this.target = (AbstractComponentConnector) target;

        // Construct the list of selectors we should match against in the
        // range selectors
        String primaryStyle = this.target.getState().primaryStyleName;
        StringBuilder selectors = new StringBuilder();
        selectors.append("." + primaryStyle);

        if (this.target.getState().styles != null
                && this.target.getState().styles.size() > 0) {
            for (String style : this.target.getState().styles) {
                // TODO decide all the combinations we want to support
                selectors.append(",." + style);
                selectors.append(",." + primaryStyle + "." + style);
                selectors.append(",." + style + "." + primaryStyle);
                selectors.append(",." + primaryStyle + "-" + style);
            }
        }

        // Allow the ID to be used as the selector as well for ranges
        if (this.target.getState().id != null) {
            selectors.append(",#" + this.target.getState().id);
        }

        // Get any breakpoints from the styles defined for this widget
        getBreakPointsFor(selectors.toString());

        // Start listening for size changes
        LayoutManager.get(getConnection()).addElementResizeListener(
                this.target.getWidget().getElement(), this);
    }

    /**
     * Build a cache of all 'width-range' and 'height-range' attribute selectors
     * found in the stylesheets.
     */
    private static void searchForBreakPoints() {
        Document doc = Browser.getDocument();
        StyleSheetList sheets = doc.getStyleSheets();

        if (sheets != null && sheets.getLength() > 0) {
            for (int i = 0, len = sheets.getLength(); i < len; i++) {
                if (sheets.item(i) instanceof CSSStyleSheet) {
                    searchStylesheetForBreakPoints((CSSStyleSheet) sheets
                            .item(i));
                }
            }
        }
    }

    /**
     * Process an individual stylesheet object. Any @import statements are
     * handled recursively. Regular rule declarations are searched for
     * 'width-range' and 'height-range' attribute selectors.
     * 
     * @param sheet
     */
    private static void searchStylesheetForBreakPoints(final CSSStyleSheet sheet) {
        // Get all the rulesets from the stylesheet
        CSSRuleList theRules = sheet.getCssRules();

        // Loop through the rulesets
        for (int i = 0, len = theRules.getLength(); i < len; i++) {
            CSSRule rule = theRules.item(i);

            if (rule.getType() == CSSRule.IMPORT_RULE) {
                // @import rule, traverse recursively
                searchStylesheetForBreakPoints(((CSSImportRule) rule)
                        .getStyleSheet());

            } else if (rule.getType() == CSSRule.STYLE_RULE
                    || rule.getType() == CSSRule.UNKNOWN_RULE) {

                // Pattern for matching [width-range] selectors
                RegExp widths = getRegExp(Dimension.WIDTH);
                // Patter for matching [height-range] selectors
                RegExp heights = getRegExp(Dimension.HEIGHT);

                // Array of all of the separate selectors in this ruleset
                String[] haystacks = ((CSSStyleRule) rule).getSelectorText()
                        .toLowerCase().split(",");

                // Loop all the selectors in this ruleset
                for (String haystack : haystacks) {
                    MatchResult result;

                    // Check for width-range matches
                    result = widths.exec(haystack);
                    if (result != null) {
                        String selector = isIE() ? result.getGroup(3) : result
                                .getGroup(1);
                        String min = isIE() ? result.getGroup(1) : result
                                .getGroup(2);
                        String max = isIE() ? result.getGroup(2) : result
                                .getGroup(3);
                        widthRangeCache.add(new BreakPoint(selector, min, max));
                    }

                    // Check for height-range matches
                    result = heights.exec(haystack);
                    if (result != null) {
                        String selector = isIE() ? result.getGroup(3) : result
                                .getGroup(1);
                        String min = isIE() ? result.getGroup(1) : result
                                .getGroup(2);
                        String max = isIE() ? result.getGroup(2) : result
                                .getGroup(3);
                        heightRangeCache
                                .add(new BreakPoint(selector, min, max));
                    }
                }
            }
        }
    }

    private static RegExp getRegExp(Dimension dimension) {
        // IE parses CSS like .class[attr="val"] into [attr="val"].class
        // so we need to check for both
        String dim = dimension.toString().toLowerCase();
        String regExpStr = "([\\.|#]\\S+)\\[" + dim
                + "-range~?=[\\\"|'](.*)-(.*)[\\\"|']\\]";
        if (isIE()) {
            regExpStr = "\\[" + dim
                    + "-range~?=[\\\"|'](.*)-(.*)[\\\"|']\\]([\\.|#]\\S+)";
        }
        return RegExp.compile(regExpStr, "i");
    }

    private static boolean isIE() {
        return Browser.getWindow().getNavigator().getAppName()
                .equals("Microsoft Internet Explorer");
    }

    /**
     * Get all matching ranges from the cache for this particular instance.
     * 
     * @param selectors
     */
    private void getBreakPointsFor(final String selectorsStr) {
        String[] selectors = selectorsStr.split(",");
        for (BreakPoint bp : widthRangeCache) {
            for (String selector : selectors) {
                if (bp.selector.equals(selector))
                    widthBreakpoints.add(bp);
            }
        }
        for (BreakPoint bp : heightRangeCache) {
            for (String selector : selectors) {
                if (bp.selector.equals(selector))
                    heightBreakpoints.add(bp);
            }
        }
    }

    @Override
    public void onElementResize(ElementResizeEvent e) {
        int width = e.getLayoutManager().getOuterWidth(e.getElement());
        int height = e.getLayoutManager().getOuterHeight(e.getElement());

        // Loop through breakpoints and see which one applies to this width
        currentWidthRanges = resolveBreakpoint(Dimension.WIDTH, width,
                e.getElement());

        if (currentWidthRanges != "") {
            target.getWidget().getElement()
                    .setAttribute("width-range", currentWidthRanges);
        } else {
            target.getWidget().getElement().removeAttribute("width-range");
        }

        // Loop through breakpoints and see which one applies to this height
        currentHeightRanges = resolveBreakpoint(Dimension.HEIGHT, height,
                e.getElement());

        if (currentHeightRanges != "") {
            target.getWidget().getElement()
                    .setAttribute("height-range", currentHeightRanges);
        } else {
            target.getWidget().getElement().removeAttribute("height-range");
        }
    }

    private String resolveBreakpoint(Dimension dimension, int size,
            Element element) {

        // Select width or height breakpoints
        List<BreakPoint> breakpoints = (dimension == Dimension.WIDTH ? widthBreakpoints
                : heightBreakpoints);

        // Output string that goes into either the "width-range" or
        // "height-range" attribute in the element
        String ranges = "";

        // Loop the breakpoints
        for (BreakPoint bp : breakpoints) {
            int min = 0;
            int max = 0;

            // Do we need to calculate the pixel value?
            if (bp.min.length() > 0 && !bp.min.equals("0")) {
                if (!bp.min.contains("px")) {
                    min = getPixelSize(bp.min, element);
                    // Calculation failed somehow, ignore this breakpoint
                    // TODO inform the developer somehow?
                    if (min == -1)
                        continue;
                } else {
                    // No, we can use the pixel value directly
                    if (bp.min.endsWith("px")) {
                        min = Integer.parseInt(bp.min.substring(0,
                                bp.min.length() - 2));
                    } else {
                        min = Integer.parseInt(bp.min);
                    }
                }
            }

            // Do we need to calculate the pixel value?
            if (bp.max.length() > 0 && !bp.max.equals("0")) {
                if (!bp.max.contains("px")) {
                    max = getPixelSize(bp.max, element);
                    // Calculation failed somehow, ignore this breakpoint
                    // TODO inform the developer somehow?
                    if (max == -1)
                        continue;
                } else {
                    // No, we can use the pixel value directly
                    if (bp.max.endsWith("px")) {
                        max = Integer.parseInt(bp.max.substring(0,
                                bp.max.length() - 2));
                    } else {
                        max = Integer.parseInt(bp.max);
                    }
                }
            }

            if (max > 0) {
                if (min <= size && size <= max) {
                    ranges += " " + bp.min + "-" + bp.max;
                }
            } else {
                if (min <= size) {
                    ranges += " " + bp.min + "-";
                }
            }
        }

        // Trim the output and return it
        return ranges.trim();
    }

    private static int getPixelSize(String size, Element context) {
        // Get the value and units from the size
        RegExp regex = RegExp.compile("^(\\d+)?(\\.\\d+)?(.{1,3})", "i");
        MatchResult match = regex.exec(size);

        String val = "0";
        if (match.getGroup(1) != null) {
            val = match.getGroup(1);
            if (match.getGroup(2) != null) {
                val += match.getGroup(2);
            }
        }
        String unit = match.getGroup(3).toLowerCase();

        // Use a temporary measuring element to get the computed size of the
        // relative units
        if (unit.equals("em") || unit.equals("rem") || unit.equals("ex")
                || unit.equals("ch")) {
            Element measure = DOM.createDiv();
            measure.getStyle().setProperty("width", size);
            context.appendChild(measure);
            int s = measure.getOffsetWidth();
            context.removeChild(measure);
            return s;
        }
        // Handle all other absolute units with basic math
        int value = Integer.parseInt(val);
        if (unit.equals("in")) {
            return value * 96;
        } else if (unit.equals("cm")) {
            return (int) (value * 37.8);
        } else if (unit.equals("mm")) {
            return (int) (value * 3.78);
        } else if (unit.equals("pt")) {
            return (value * 96) / 72;
        } else if (unit.equals("pc")) {
            return ((value * 96) / 72) * 12;
        }
        return -1; // fail
    }

    private enum Dimension {
        WIDTH, HEIGHT;
    }

}
