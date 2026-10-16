import XCTest
import LilacShared
@testable import LilacAnime

final class CatalogExtraFiltersTests: XCTestCase {
    func testExtraOnlySelectionIsActiveAndZeroScoreSurvivesBridge() throws {
        var selection = DesktopCatalogSelection()
        XCTAssertFalse(selection.active)
        selection.extras = ["scoreMin": "0", "adult": "false"]
        XCTAssertTrue(selection.active)
        let filter = AnimeSnapshot.shared.extendedFilter(genre: "", year: "", season: "", format: "", sort: "popular", extras: selection.extras)
        XCTAssertEqual(filter.extras["scoreMin"], "0")
        XCTAssertEqual(filter.extras["adult"], "false")
        try DesktopCatalogTaxonomy.shared.validate(source: "miruro", filter: filter, facets: DesktopCatalogTaxonomy.shared.miruro())
    }
    func testInvalidExtraFilterReturnsSwiftError() {
        let filter = AnimeSnapshot.shared.extendedFilter(genre: "", year: "", season: "", format: "", sort: "popular", extras: ["scoreMin": "101"])
        XCTAssertThrowsError(try DesktopCatalogTaxonomy.shared.validate(source: "miruro", filter: filter, facets: DesktopCatalogTaxonomy.shared.miruro()))
    }
}
